package com.spotshare.reservation;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end reservation workflow over HTTP against a real PostgreSQL
 * database (Flyway runs the real migrations): register → create space →
 * share → book → address reveal → idempotent replay → conflict → cancel →
 * re-book, plus the privacy rules on the detail view.
 *
 * <p>Runs against the {@code test} profile datasource. Aborts with a clear
 * message when no database is reachable from the JVM (e.g. sandboxes that
 * block database connections) instead of failing — CI runs it for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReservationIntegrationTest {

    @BeforeAll
    static void requireDatabase() {
        Assumptions.assumeTrue(canReachPostgres(),
                "Aborting: PostgreSQL is not reachable from the JVM here "
                + "(this sandbox blocks database connections). "
                + "Run with a local PostgreSQL to execute this test.");
    }

    /** A real JDBC handshake, not just a TCP connect (proxies can fake those). */
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

    private String register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-9",\
                                "firstName":"Holly","lastName":"Host"}\
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }

    private String auth(String token) {
        return "Bearer " + token;
    }

    private String createSpace(String token, String label) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/spaces")
                        .header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"%s","address":"123 Wacker Dr","city":"Chicago",\
                                "state":"IL","zipCode":"60601","latitude":41.8858,\
                                "longitude":-87.6189,"areaLabel":"West Loop",\
                                "parkingType":"ASSIGNED_SPACE","vehicleSizes":["SEDAN"],\
                                "covered":false,"evCharging":false,\
                                "authorizationConfirmed":true}\
                                """.formatted(label)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();
    }

    private void share(String token, String spaceId, OffsetDateTime returnTime, String rateCents)
            throws Exception {
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"returnTime":"%s","hourlyRateCents":%s}\
                                """.formatted(returnTime, rateCents)))
                .andExpect(status().isCreated());
    }

    private MvcResult book(String token, String spaceId, OffsetDateTime arrival,
                           OffsetDateTime departure, String idempotencyKey) throws Exception {
        var req = post("/api/v1/reservations")
                .header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"spaceId":"%s","arrival":"%s","departure":"%s"}\
                        """.formatted(spaceId, arrival, departure));
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(req).andReturn();
    }

    @Test
    void fullReservationWorkflow() throws Exception {
        String hostToken = register("phase5-host@example.com");
        String spaceId = createSpace(hostToken, "B17");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(hostToken, spaceId, now.plusHours(4), "300");

        String driverToken = register("phase5-driver@example.com");
        OffsetDateTime arrival = now.plusHours(1);
        OffsetDateTime departure = now.plusHours(3);

        // 1. Book: 201, priced 2 h × $3 = $6.00, code in the SP-XXXXX format.
        MvcResult booked = book(driverToken, spaceId, arrival, departure, "phase5-key-1");
        assertThat(booked.getResponse().getStatus()).isEqualTo(201);
        JsonNode booking = objectMapper.readTree(booked.getResponse().getContentAsString());
        String reservationId = booking.get("id").asText();
        String code = booking.get("code").asText();
        assertThat(code).matches("SP-[A-HJ-NP-Z2-9]{5}");
        assertThat(booking.get("totalCents").asInt()).isEqualTo(600);
        assertThat(booking.get("status").asText()).isEqualTo("CONFIRMED");
        // The summary never carries the exact address.
        assertThat(booking.has("address")).isFalse();

        // 2. The driver sees the exact address on the authorized detail view.
        mvc.perform(get("/api/v1/reservations/" + reservationId)
                        .header("Authorization", auth(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("123 Wacker Dr"))
                .andExpect(jsonPath("$.spaceLabel").value("B17"))
                .andExpect(jsonPath("$.code").value(code));

        // 3. The host sees it too.
        mvc.perform(get("/api/v1/reservations/" + reservationId)
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("123 Wacker Dr"));

        // 4. A stranger gets 403 — never the address.
        String strangerToken = register("phase5-stranger@example.com");
        mvc.perform(get("/api/v1/reservations/" + reservationId)
                        .header("Authorization", auth(strangerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_RESERVATION"));

        // 5. It shows up in the driver's list.
        mvc.perform(get("/api/v1/reservations/mine")
                        .header("Authorization", auth(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value(code));

        // 6. Replaying the idempotency key returns the original (200), not a duplicate.
        MvcResult replayed = book(driverToken, spaceId, arrival, departure, "phase5-key-1");
        assertThat(replayed.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(replayed.getResponse().getContentAsString())
                .get("code").asText()).isEqualTo(code);

        // 7. An overlapping booking by another driver gets the friendly 409.
        String driver2Token = register("phase5-driver2@example.com");
        MvcResult conflict = book(driver2Token, spaceId,
                arrival.plusMinutes(30), departure.plusMinutes(30), "phase5-key-2");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        JsonNode conflictBody = objectMapper.readTree(conflict.getResponse().getContentAsString());
        assertThat(conflictBody.get("code").asText()).isEqualTo("SPACE_JUST_RESERVED");
        assertThat(conflictBody.get("message").asText()).contains("just reserved");

        // 8. An adjacent booking is allowed: it starts exactly when this one ends.
        MvcResult adjacent = book(driver2Token, spaceId, departure, departure.plusHours(1), "phase5-key-3");
        assertThat(adjacent.getResponse().getStatus()).isEqualTo(201);

        // 9. The host cannot book their own space.
        MvcResult selfBook = book(hostToken, spaceId, arrival, departure, "phase5-key-4");
        assertThat(selfBook.getResponse().getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(selfBook.getResponse().getContentAsString())
                .get("code").asText()).isEqualTo("CANNOT_BOOK_OWN_SPACE");

        // 10. Cancel: 200, status flips, and cancelling again is a no-op success.
        mvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel")
                        .header("Authorization", auth(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel")
                        .header("Authorization", auth(driverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 11. The cancelled period is bookable again — the backstop only
        //     guards CONFIRMED reservations.
        MvcResult rebook = book(driver2Token, spaceId, arrival, departure, "phase5-key-5");
        assertThat(rebook.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void bookingValidation_rejectsBadInput() throws Exception {
        String hostToken = register("phase5-validation-host@example.com");
        String spaceId = createSpace(hostToken, "B18");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(hostToken, spaceId, now.plusHours(4), "null");
        String driverToken = register("phase5-validation-driver@example.com");

        // Inverted period.
        MvcResult inverted = book(driverToken, spaceId, now.plusHours(2), now.plusHours(1), null);
        assertThat(inverted.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(inverted.getResponse().getContentAsString())
                .get("code").asText()).isEqualTo("INVALID_PERIOD");

        // Arrival in the past.
        MvcResult past = book(driverToken, spaceId, now.minusHours(2), now.minusHours(1), null);
        assertThat(past.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(past.getResponse().getContentAsString())
                .get("code").asText()).isEqualTo("ARRIVAL_IN_PAST");

        // Period outside any share window (the window ends at now+4h).
        MvcResult outside = book(driverToken, spaceId, now.plusHours(5), now.plusHours(6), null);
        assertThat(outside.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(outside.getResponse().getContentAsString())
                .get("code").asText()).isEqualTo("PERIOD_NOT_AVAILABLE");

        // Unknown space.
        MvcResult unknown = book(driverToken, UUID.randomUUID().toString(),
                now.plusHours(1), now.plusHours(2), null);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void freeShareBooksForZero() throws Exception {
        String hostToken = register("phase5-free-host@example.com");
        String spaceId = createSpace(hostToken, "B19");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(hostToken, spaceId, now.plusHours(4), "null");
        String driverToken = register("phase5-free-driver@example.com");

        MvcResult booked = book(driverToken, spaceId, now.plusHours(1), now.plusHours(2), "phase5-free-key");
        assertThat(booked.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(booked.getResponse().getContentAsString());
        assertThat(body.get("totalCents").asInt()).isZero();
        assertThat(body.get("hourlyRateCents").isNull()).isTrue();
    }
}
