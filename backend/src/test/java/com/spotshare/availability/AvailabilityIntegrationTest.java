package com.spotshare.availability;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;

/**
 * End-to-end availability workflow over HTTP against a real PostgreSQL
 * database (Flyway runs the real migrations): register → create space →
 * Share My Spot → list → remove, plus overlap rejection and derived expiry
 * against the real overlap query.
 *
 * <p>Runs against the {@code test} profile datasource. Aborts with a clear
 * message when no database is reachable from the JVM (e.g. sandboxes that
 * block database connections) instead of failing — CI runs it for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AvailabilityIntegrationTest {

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

    @Autowired
    private ParkingSpaceRepository spaces;

    @Autowired
    private AvailabilityWindowRepository windows;

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

    private String shareJson(OffsetDateTime returnTime, String rateCents) {
        return """
                {"returnTime":"%s","hourlyRateCents":%s}\
                """.formatted(returnTime, rateCents);
    }

    @Test
    void fullShareWorkflow() throws Exception {
        String hostToken = register("phase3-host@example.com");
        String spaceId = createSpace(hostToken, "B17");
        OffsetDateTime returnTime = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);

        // 1. Share: 201, live immediately, rate in cents.
        MvcResult shared = mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(returnTime, "300")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.spaceId").value(spaceId))
                .andExpect(jsonPath("$.hourlyRateCents").value(300))
                .andExpect(jsonPath("$.live").value(true))
                .andReturn();
        String windowId = objectMapper.readTree(shared.getResponse().getContentAsString())
                .get("id").asText();

        // 2. The space now shows AVAILABLE in My Parking.
        mvc.perform(get("/api/v1/spaces/mine")
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayState").value("AVAILABLE"));

        // 3. List shows the window; /availability/mine shows it across spaces.
        mvc.perform(get("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(windowId));
        mvc.perform(get("/api/v1/availability/mine")
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 4. Overlapping share is rejected with the friendly 409.
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(returnTime.plusHours(1), "null")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAPPING_WINDOW"));

        // 5. Another user's space is 403 — never the data.
        String strangerToken = register("phase3-stranger@example.com");
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(strangerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(returnTime, "null")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SPACE"));

        // 6. Removing a not-started window works and is idempotent.
        //    (The window already started, so remove a future one instead —
        //    insert it directly to control the start time.)
        ParkingSpace space = spaces.findById(UUID.fromString(spaceId)).orElseThrow();
        AvailabilityWindow future = windows.save(new AvailabilityWindow(space,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(3),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(5),
                WindowSource.MANUAL, null, OffsetDateTime.now(ZoneOffset.UTC)));
        mvc.perform(delete("/api/v1/availability/" + future.getId())
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isNoContent());
        assertThat(windows.findById(future.getId())).isEmpty();
        // Second delete: no-op success.
        mvc.perform(delete("/api/v1/availability/" + future.getId())
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isNoContent());

        // 7. A started window cannot be removed.
        mvc.perform(delete("/api/v1/availability/" + windowId)
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_ALREADY_STARTED"));
    }

    @Test
    void shareValidation_rejectsBadInput() throws Exception {
        String hostToken = register("phase3-validation@example.com");
        String spaceId = createSpace(hostToken, "B18");
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

        // Past return time.
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(past, "null")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_RETURN_TIME"));

        // Under 30 minutes.
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10), "null")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_TOO_SHORT"));

        // Zero and negative price.
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2), "0")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PRICE"));

        // Over the $100/hr cap.
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2), "10001")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PRICE"));

        // Missing return time.
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hourlyRateCents\":300}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void adjacencyAndExpiry_againstRealDatabase() throws Exception {
        String hostToken = register("phase3-adjacent@example.com");
        String spaceId = createSpace(hostToken, "B19");
        UUID sid = UUID.fromString(spaceId);
        ParkingSpace space = spaces.findById(sid).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Adjacent windows do NOT overlap: [now-2h, now) next to [now, now+2h).
        AvailabilityWindow first = windows.save(new AvailabilityWindow(space,
                now.minusHours(2), now, WindowSource.MANUAL, null, OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(windows.findOverlapping(sid, now, now.plusHours(2))).isEmpty();

        // A truly overlapping period is found by the real query.
        assertThat(windows.findOverlapping(sid, now.minusHours(1), now.plusHours(1)))
                .extracting(w -> w.getId()).contains(first.getId());

        // Expiry is derived: the ended window is not live and is excluded
        // from the upcoming/active list, while a current share stays live.
        assertThat(windows.findLive(sid, now)).isEmpty();
        mvc.perform(get("/api/v1/spaces/" + spaceId + "/availability")
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        AvailabilityWindow current = windows.save(new AvailabilityWindow(space,
                now, now.plusHours(2), WindowSource.MANUAL, 250, OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(windows.findLive(sid, now)).extracting(w -> w.getId())
                .containsExactly(current.getId());
        mvc.perform(get("/api/v1/spaces/mine")
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayState").value("AVAILABLE"));
    }
}
