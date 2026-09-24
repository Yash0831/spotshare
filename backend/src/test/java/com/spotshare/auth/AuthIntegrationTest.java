package com.spotshare.auth;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
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
import com.spotshare.user.UserRepository;

/**
 * End-to-end auth workflow over HTTP against a real PostgreSQL database
 * (Flyway runs the real migrations): register → login → me → refresh
 * (rotation) → logout, plus the failure modes.
 *
 * <p>Runs against the {@code test} profile datasource
 * (see src/test/resources/application.yml). Aborts with a clear message when
 * no database is reachable from the JVM (e.g. sandboxes that block database
 * connections) instead of failing — CI runs it for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthIntegrationTest {

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
    private UserRepository users;

    private String registerBody(String email) {
        return """
                {"email":"%s","password":"correct-horse-9","firstName":"Test","lastName":"Driver","phone":"+13125550100"}
                """.formatted(email);
    }

    private JsonNode register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void fullWorkflow_registerLoginMeRefreshLogout() throws Exception {
        String email = "workflow@example.com";
        JsonNode reg = register(email);
        String refreshToken = reg.get("refreshToken").asText();

        // me with the access token
        mvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + reg.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.firstName").value("Test"));

        // refresh rotates: new pair issued
        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn();
        String newRefresh = objectMapper.readTree(
                rotated.getResponse().getContentAsString()).get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(refreshToken);

        // old refresh token is now dead (single-use rotation)
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // logout revokes the current refresh token
        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isUnauthorized());

        // password is stored hashed with bcrypt cost 12, never plain
        var stored = users.findByEmail(email).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2a$12$");
        assertThat(stored.getPasswordHash()).doesNotContain("correct-horse-9");
    }

    @Test
    void duplicateRegistrationIsRejected() throws Exception {
        String email = "dupe@example.com";
        register(email);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void invalidRegistrationPayloadsAreRejected() throws Exception {
        // bad email
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // short password
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"short@example.com","password":"tiny","firstName":"A","lastName":"B"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // missing names
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"noname@example.com","password":"long-enough-password"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void loginRejectsBadCredentialsWithoutEnumeration() throws Exception {
        String email = "login@example.com";
        register(email);

        // Same status and code whether the email exists or not: no account enumeration.
        String wrongPassword = loginFailureBody(email, "wrong-password");
        String unknownEmail = loginFailureBody("nobody@example.com", "wrong-password");

        assertThat(errorCode(wrongPassword)).isEqualTo("INVALID_CREDENTIALS");
        assertThat(errorCode(unknownEmail)).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        // no token
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // garbage token
        mvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void refreshWithUnknownTokenIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"AAAA-unknown-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutIsIdempotent() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"AAAA-unknown-token\"}"))
                .andExpect(status().isNoContent());
    }

    // --- helpers ---------------------------------------------------------------

    private String loginFailureBody(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String errorCode(String responseBody) throws Exception {
        return objectMapper.readTree(responseBody).get("code").asText();
    }
}
