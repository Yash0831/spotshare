package com.spotshare.parking;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * End-to-end space workflow over HTTP against a real PostgreSQL database
 * (Flyway runs the real migrations): register → create space → photos →
 * edit → deactivate, plus owner-authorization and privacy checks.
 *
 * <p>Runs against the {@code test} profile datasource. Aborts with a clear
 * message when no database is reachable from the JVM (e.g. sandboxes that
 * block database connections) instead of failing — CI runs it for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ParkingSpaceIntegrationTest {

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

    private String spaceJson(boolean authorized, String label) {
        return """
                {"label":"%s","address":"123 Wacker Dr","city":"Chicago","state":"il",\
                "zipCode":"60601","latitude":41.8858,"longitude":-87.6189,\
                "areaLabel":"West Loop","parkingType":"ASSIGNED_SPACE",\
                "description":"Covered spot near the elevators.",\
                "vehicleSizes":["SEDAN","SUV"],"heightLimitInches":84,"covered":true,\
                "evCharging":false,"parkingInstructions":"Gate code 1234, level 2.",\
                "authorizationConfirmed":%s}\
                """.formatted(label, authorized);
    }

    @Test
    void fullHostWorkflow() throws Exception {
        String hostToken = register("phase2-host@example.com");

        // 1. Creation without the authorization confirmation is rejected.
        mvc.perform(post("/api/v1/spaces")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spaceJson(false, "B17")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 2. Valid creation: 201, owner DTO carries the private fields.
        MvcResult created = mvc.perform(post("/api/v1/spaces")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spaceJson(true, "B17")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.address").value("123 Wacker Dr"))
                .andExpect(jsonPath("$.state").value("IL"))
                .andExpect(jsonPath("$.parkingInstructions").value("Gate code 1234, level 2."))
                .andExpect(jsonPath("$.authorizationConfirmed").value(true))
                .andExpect(jsonPath("$.authorizationConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.photos").isArray())
                .andReturn();
        String spaceId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();

        // 3. My Parking lists it.
        mvc.perform(get("/api/v1/spaces/mine")
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(spaceId));

        // 4. Another user gets 403 on read, update, and deactivation —
        //    never the private data.
        String strangerToken = register("phase2-stranger@example.com");
        mvc.perform(get("/api/v1/spaces/" + spaceId)
                        .header("Authorization", auth(strangerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SPACE"));
        mvc.perform(put("/api/v1/spaces/" + spaceId)
                        .header("Authorization", auth(strangerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spaceJson(true, "B17")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/spaces/" + spaceId)
                        .header("Authorization", auth(strangerToken)))
                .andExpect(status().isForbidden());

        // 5. Owner edits the space.
        mvc.perform(put("/api/v1/spaces/" + spaceId)
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spaceJson(true, "B18")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("B18"))
                .andExpect(jsonPath("$.authorizationConfirmed").value(true));

        // 6. Photo upload round-trip: upload → public content → delete.
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01};
        var file = new MockMultipartFile("photo", "p.jpg", "image/jpeg", jpeg);
        MvcResult uploaded = mvc.perform(multipart("/api/v1/spaces/" + spaceId + "/photos")
                        .file(file)
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andReturn();
        JsonNode photo = objectMapper.readTree(uploaded.getResponse().getContentAsString());
        String photoId = photo.get("id").asText();
        String contentUrl = photo.get("contentUrl").asText();

        // The content endpoint is public: no auth header.
        MvcResult content = mvc.perform(get(contentUrl))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(content.getResponse().getContentAsByteArray()).isEqualTo(jpeg);

        mvc.perform(delete("/api/v1/spaces/" + spaceId + "/photos/" + photoId)
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isNoContent());
        mvc.perform(get(contentUrl)).andExpect(status().isNotFound());

        // 7. Deactivation is soft and idempotent; the record is retained.
        mvc.perform(delete("/api/v1/spaces/" + spaceId)
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(delete("/api/v1/spaces/" + spaceId)
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/spaces/" + spaceId)
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void validationFailures_returnFriendlyErrors() throws Exception {
        String hostToken = register("phase2-validation@example.com");

        // Bad coordinates.
        mvc.perform(post("/api/v1/spaces")
                        .header("Authorization", auth(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spaceJson(true, "B17")
                                .replace("\"latitude\":41.8858", "\"latitude\":999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // Unknown space.
        mvc.perform(get("/api/v1/spaces/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", auth(hostToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SPACE_NOT_FOUND"));
    }
}
