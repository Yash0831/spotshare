package com.spotshare.reservation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The spec's required concurrency proof (§9): two drivers book the same
 * overlapping period at the same instant → exactly one CONFIRMED, the other
 * gets the friendly 409. The per-space advisory lock serializes the two
 * bookings; the exclusion constraint is the backstop.
 *
 * <p>Deliberately NOT {@code @Transactional}: the racing threads need
 * committed rows to see. Setup and teardown go through HTTP/JDBC so every
 * row this test creates is removed afterwards in FK-safe order.
 *
 * <p>Aborts cleanly when the JVM cannot reach PostgreSQL (sandbox) — CI
 * runs it for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReservationConcurrencyTest {

    @BeforeAll
    static void requireDatabase() {
        Assumptions.assumeTrue(canReachPostgres(),
                "Aborting: PostgreSQL is not reachable from the JVM here "
                + "(this sandbox blocks database connections). "
                + "Run with a local PostgreSQL to execute this test.");
    }

    private static boolean canReachPostgres() {
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/spotshare_test?sslmode=disable&connectTimeout=3",
                "postgres", "postgres")) {
            return c.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String runTag;
    private String spaceId;
    private final List<String> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        runTag = UUID.randomUUID().toString().substring(0, 8);
        String hostToken = register("race-host-" + runTag + "@example.com");
        spaceId = createSpace(hostToken);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"returnTime":"%s","hourlyRateCents":300}\
                                """.formatted(now.plusHours(4))))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() {
        // FK-safe cleanup of everything this run created.
        jdbc.update("DELETE FROM reservations WHERE space_id = ?::uuid", spaceId);
        jdbc.update("DELETE FROM availability_windows WHERE space_id = ?::uuid", spaceId);
        jdbc.update("DELETE FROM parking_photos WHERE space_id = ?::uuid", spaceId);
        jdbc.update("DELETE FROM parking_spaces WHERE id = ?::uuid", spaceId);
        for (String userId : userIds) {
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?::uuid", userId);
        }
        for (String userId : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?::uuid", userId);
        }
    }

    private String register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-9",\
                                "firstName":"Rae","lastName":"Racer"}\
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        userIds.add(body.get("user").get("id").asText());
        return body.get("accessToken").asText();
    }

    private String createSpace(String hostToken) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/spaces")
                        .header("Authorization", "Bearer " + hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"R1","address":"9 Raceway Ln","city":"Philadelphia",\
                                "state":"PA","zipCode":"19103","latitude":39.9526,\
                                "longitude":-75.1652,"areaLabel":"Center City",\
                                "parkingType":"PRIVATE_LOT","vehicleSizes":["SEDAN"],\
                                "covered":false,"evCharging":false,\
                                "authorizationConfirmed":true}\
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();
    }

    @Test
    void twoSimultaneousBookingsYieldExactlyOneConfirmation() throws Exception {
        String driverAToken = register("race-a-" + runTag + "@example.com");
        String driverBToken = register("race-b-" + runTag + "@example.com");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime arrival = now.plusHours(1);
        OffsetDateTime departure = now.plusHours(2);
        String body = """
                {"spaceId":"%s","arrival":"%s","departure":"%s"}\
                """.formatted(spaceId, arrival, departure);

        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> a = pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/reservations")
                                .header("Authorization", "Bearer " + driverAToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            });
            Future<MvcResult> b = pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/reservations")
                                .header("Authorization", "Bearer " + driverBToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            });
            gate.countDown();

            int statusA = a.get(60, TimeUnit.SECONDS).getResponse().getStatus();
            int statusB = b.get(60, TimeUnit.SECONDS).getResponse().getStatus();

            // Exactly one success and one friendly conflict — never two bookings.
            assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder(201, 409);

            MvcResult loser = statusA == 409 ? a.get() : b.get();
            JsonNode loserBody = objectMapper.readTree(loser.getResponse().getContentAsString());
            assertThat(loserBody.get("code").asText()).isEqualTo("SPACE_JUST_RESERVED");
            assertThat(loserBody.get("message").asText()).contains("just reserved");

            Long confirmed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reservations WHERE space_id = ?::uuid AND status = 'CONFIRMED'",
                    Long.class, spaceId);
            assertThat(confirmed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
