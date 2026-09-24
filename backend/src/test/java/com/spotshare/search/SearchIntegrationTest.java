package com.spotshare.search;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.hasSize;
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
 * End-to-end discovery against a real PostgreSQL + PostGIS database (Flyway
 * runs the real migrations): register → create spaces → Share My Spot →
 * search as a guest. Covers radius filtering, full-period containment,
 * price filtering, distance ordering, and the privacy-safe DTO over HTTP.
 *
 * <p>Aborts with a clear message when no database is reachable from the JVM
 * (e.g. sandboxes that block database connections) instead of failing —
 * CI runs it for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SearchIntegrationTest {

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
        return "Bearer " + body.get("accessToken").asText();
    }

    private String createSpace(String auth, String label, double lat, double lng) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/spaces")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"%s","address":"123 Wacker Dr","city":"Chicago",\
                                "state":"IL","zipCode":"60601","latitude":%f,\
                                "longitude":%f,"areaLabel":"West Loop",\
                                "parkingType":"ASSIGNED_SPACE","vehicleSizes":["SEDAN"],\
                                "covered":true,"evCharging":false,\
                                "authorizationConfirmed":true}\
                                """.formatted(label, lat, lng)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();
    }

    private void share(String auth, String spaceId, OffsetDateTime returnTime, String rateCents)
            throws Exception {
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"returnTime":"%s","hourlyRateCents":%s}\
                                """.formatted(returnTime, rateCents)))
                .andExpect(status().isCreated());
    }

    private String searchUrl(double lat, double lng, OffsetDateTime arrival,
                             OffsetDateTime departure, String extra) {
        return "/api/v1/spaces/search?lat=" + lat + "&lng=" + lng
                + "&arrival=" + arrival + "&departure=" + departure + extra;
    }

    @Test
    void search_findsSharedSpaceWithPrivacySafeDto() throws Exception {
        String auth = register("search-host@example.com");
        String spaceId = createSpace(auth, "B17", 41.8858, -87.6189);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(auth, spaceId, now.plusHours(4), "300");

        OffsetDateTime arrival = now.plusHours(1);
        OffsetDateTime departure = now.plusHours(2);

        // No auth header: discovery is public.
        mvc.perform(get(searchUrl(41.8858, -87.6189, arrival, departure, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].id").value(spaceId))
                .andExpect(jsonPath("$.results[0].areaLabel").value("West Loop"))
                .andExpect(jsonPath("$.results[0].city").value("Chicago"))
                .andExpect(jsonPath("$.results[0].hostName").value("Holly H."))
                .andExpect(jsonPath("$.results[0].hourlyRateCents").value(300))
                .andExpect(jsonPath("$.results[0].estimatedTotalCents").value(300))
                .andExpect(jsonPath("$.results[0].distanceMiles").value(0.0))
                .andExpect(jsonPath("$.results[0].approxLatitude").exists())
                // Privacy: these fields must never appear in a public DTO.
                .andExpect(jsonPath("$.results[0].address").doesNotExist())
                .andExpect(jsonPath("$.results[0].label").doesNotExist())
                .andExpect(jsonPath("$.results[0].areaLabel").exists())
                .andExpect(jsonPath("$.results[0].parkingInstructions").doesNotExist())
                .andExpect(jsonPath("$.results[0].email").doesNotExist());
    }

    @Test
    void search_excludesSpacesOutsideRadius() throws Exception {
        String auth = register("radius-host@example.com");
        String nearId = createSpace(auth, "N1", 41.8858, -87.6189);
        String farId = createSpace(auth, "F1", 42.5, -88.5);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(auth, nearId, now.plusHours(4), "null");
        share(auth, farId, now.plusHours(4), "null");

        mvc.perform(get(searchUrl(41.8858, -87.6189,
                        now.plusHours(1), now.plusHours(2), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].id").value(nearId));
    }

    @Test
    void search_excludesWindowsThatDoNotContainTheWholePeriod() throws Exception {
        String auth = register("contain-host@example.com");
        String fullId = createSpace(auth, "C1", 41.8858, -87.6189);
        String partialId = createSpace(auth, "C2", 41.8859, -87.6190);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(auth, fullId, now.plusHours(4), "null");
        // Window [now+3h, now+5h) only overlaps the searched [now+1h, now+2h).
        share(auth, partialId, now.plusHours(5), "null");

        mvc.perform(get(searchUrl(41.8858, -87.6189,
                        now.plusHours(1), now.plusHours(2), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].id").value(fullId));
    }

    @Test
    void search_maxPriceExcludesExpensiveSpacesButKeepsFreeOnes() throws Exception {
        String auth = register("price-host@example.com");
        String paidId = createSpace(auth, "P1", 41.8858, -87.6189);
        String freeId = createSpace(auth, "P2", 41.8859, -87.6190);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(auth, paidId, now.plusHours(4), "300");
        share(auth, freeId, now.plusHours(4), "null");

        mvc.perform(get(searchUrl(41.8858, -87.6189,
                        now.plusHours(1), now.plusHours(2), "&maxPrice=1.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].id").value(freeId));
    }

    @Test
    void search_ordersByDistanceNearestFirst() throws Exception {
        String auth = register("order-host@example.com");
        String fartherId = createSpace(auth, "O1", 41.8958, -87.6189);
        String nearerId = createSpace(auth, "O2", 41.8868, -87.6189);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(auth, fartherId, now.plusHours(4), "null");
        share(auth, nearerId, now.plusHours(4), "null");

        mvc.perform(get(searchUrl(41.8858, -87.6189,
                        now.plusHours(1), now.plusHours(2), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[0].id").value(nearerId))
                .andExpect(jsonPath("$.results[1].id").value(fartherId));
    }

    @Test
    void search_excludesSpaceWithConflictingReservation() throws Exception {
        String hostAuth = register("reserved-host@example.com");
        String spaceId = createSpace(hostAuth, "R1", 41.8858, -87.6189);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        share(hostAuth, spaceId, now.plusHours(4), "300");

        String driverAuth = register("reserved-driver@example.com");
        OffsetDateTime arrival = now.plusHours(1);
        OffsetDateTime departure = now.plusHours(2);
        String reservationId = book(driverAuth, spaceId, arrival, departure);

        // The reserved period no longer lists the space…
        mvc.perform(get(searchUrl(41.8858, -87.6189, arrival, departure, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(0)));

        // …but an adjacent period still does.
        mvc.perform(get(searchUrl(41.8858, -87.6189, departure, departure.plusHours(1), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].id").value(spaceId));

        // Cancelling releases the period: the space lists again.
        mvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel")
                        .header("Authorization", driverAuth))
                .andExpect(status().isOk());
        mvc.perform(get(searchUrl(41.8858, -87.6189, arrival, departure, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].id").value(spaceId));
    }

    /** Books a space for the driver; returns the reservation id. */
    private String book(String driverAuth, String spaceId,
                        OffsetDateTime arrival, OffsetDateTime departure) throws Exception {
        MvcResult booked = mvc.perform(post("/api/v1/reservations")
                        .header("Authorization", driverAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spaceId":"%s","arrival":"%s","departure":"%s"}\
                                """.formatted(spaceId, arrival, departure)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(booked.getResponse().getContentAsString())
                .get("id").asText();
    }
}
