package com.spotshare.availability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import com.spotshare.auth.AuthenticatedUser;
import com.spotshare.auth.JwtAuthenticationFilter;
import com.spotshare.auth.SecurityConfig;
import com.spotshare.availability.dto.AvailabilityWindowDto;
import com.spotshare.common.ApiException;
import com.spotshare.config.AppProperties;
import com.spotshare.user.Role;

import jakarta.servlet.FilterChain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice for availability: Bean Validation, the JWT security chain,
 * and the error envelope — no database. Service logic is covered by
 * {@link AvailabilityServiceTest}; database-backed flows live in
 * {@link AvailabilityIntegrationTest}.
 */
@WebMvcTest(AvailabilityController.class)
@Import(SecurityConfig.class)
class AvailabilityWebSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AvailabilityService availability;

    @MockBean
    private JwtAuthenticationFilter jwtFilter;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AppProperties appProperties() {
            return new AppProperties(
                    new AppProperties.Cors("http://localhost:5173"),
                    new AppProperties.Jwt("test-secret-that-is-long-enough-for-hs256!!", 15, 30));
        }
    }

    private final UUID hostId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID windowId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        doAnswer(inv -> {
            ((FilterChain) inv.getArgument(2))
                    .doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());
    }

    /** Simulates the JWT filter accepting the token for the host user. */
    private void authenticate() throws Exception {
        doAnswer(inv -> {
            var chain = (FilterChain) inv.getArgument(2);
            var auth = new UsernamePasswordAuthenticationToken(
                    new AuthenticatedUser(hostId, "host@example.com", Role.USER),
                    null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());
    }

    private AvailabilityWindowDto windowDto(boolean live) {
        OffsetDateTime now = OffsetDateTime.now();
        return new AvailabilityWindowDto(windowId, spaceId,
                now.minusMinutes(10), now.plusHours(2), WindowSource.MANUAL, 300,
                live, now.minusMinutes(10));
    }

    private String shareJson(String returnTime, String rateCents) {
        return """
                {"returnTime":"%s","hourlyRateCents":%s}\
                """.formatted(returnTime, rateCents);
    }

    @Test
    void share_withoutToken_returns401() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson("2030-01-01T18:00:00Z", "null")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void share_missingReturnTime_returns400() throws Exception {
        authenticate();

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hourlyRateCents\":300}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void share_valid_returns201WithWindow() throws Exception {
        authenticate();
        given(availability.share(eq(hostId), eq(spaceId), any())).willReturn(windowDto(true));

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson("2030-01-01T18:00:00Z", "300")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(windowId.toString()))
                .andExpect(jsonPath("$.spaceId").value(spaceId.toString()))
                .andExpect(jsonPath("$.hourlyRateCents").value(300))
                .andExpect(jsonPath("$.live").value(true));
    }

    @Test
    void share_overlappingWindow_returns409Envelope() throws Exception {
        authenticate();
        given(availability.share(eq(hostId), eq(spaceId), any()))
                .willThrow(ApiException.conflict("OVERLAPPING_WINDOW",
                        "This space is already shared for part of that time."));

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson("2030-01-01T18:00:00Z", "null")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OVERLAPPING_WINDOW"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void share_otherUsersSpace_returns403Envelope() throws Exception {
        authenticate();
        given(availability.share(eq(hostId), eq(spaceId), any()))
                .willThrow(ApiException.forbidden("NOT_YOUR_SPACE",
                        "This parking space belongs to another account."));

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareJson("2030-01-01T18:00:00Z", "null")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SPACE"));
    }

    @Test
    void listForSpace_withoutToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/spaces/" + spaceId + "/availability"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listForSpace_returnsWindows() throws Exception {
        authenticate();
        given(availability.listWindows(eq(hostId), eq(spaceId)))
                .willReturn(List.of(windowDto(true), windowDto(false)));

        mvc.perform(get("/api/v1/spaces/" + spaceId + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].live").value(true));
    }

    @Test
    void removeWindow_withoutToken_returns401() throws Exception {
        mvc.perform(delete("/api/v1/availability/" + windowId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removeWindow_started_returns422Envelope() throws Exception {
        authenticate();
        org.mockito.Mockito.doThrow(ApiException.unprocessable("WINDOW_ALREADY_STARTED",
                        "This share has already started."))
                .when(availability).removeWindow(eq(hostId), eq(windowId));

        mvc.perform(delete("/api/v1/availability/" + windowId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_ALREADY_STARTED"));
    }

    @Test
    void removeWindow_valid_returns204() throws Exception {
        authenticate();

        mvc.perform(delete("/api/v1/availability/" + windowId))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Return early (Phase 7)
    // ------------------------------------------------------------------

    private String returnEarlyJson(String newReturnTime) {
        return "{\"newReturnTime\":\"%s\"}".formatted(newReturnTime);
    }

    @Test
    void returnEarly_withoutToken_returns401() throws Exception {
        mvc.perform(post("/api/v1/availability/" + windowId + "/return-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnEarlyJson("2030-01-01T18:00:00Z")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnEarly_missingNewReturnTime_returns400() throws Exception {
        authenticate();

        mvc.perform(post("/api/v1/availability/" + windowId + "/return-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returnEarly_valid_returns200WithShrunkWindow() throws Exception {
        authenticate();
        given(availability.returnEarly(eq(hostId), eq(windowId), any()))
                .willReturn(windowDto(true));

        mvc.perform(post("/api/v1/availability/" + windowId + "/return-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnEarlyJson("2030-01-01T18:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(windowId.toString()))
                .andExpect(jsonPath("$.live").value(true));
    }

    @Test
    void returnEarly_blockedByReservation_carriesEarliestReturnInDetails() throws Exception {
        authenticate();
        String earliest = "2030-01-01T19:30:00Z";
        given(availability.returnEarly(eq(hostId), eq(windowId), any()))
                .willThrow(ApiException.unprocessable("RETURN_BLOCKED_BY_RESERVATION",
                        "Your space is reserved until 7:30 PM. Earliest available return: 7:30 PM.",
                        java.util.Map.of("earliestReturnTime", earliest)));

        mvc.perform(post("/api/v1/availability/" + windowId + "/return-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnEarlyJson("2030-01-01T18:00:00Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETURN_BLOCKED_BY_RESERVATION"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Earliest available return")))
                .andExpect(jsonPath("$.details.earliestReturnTime").value(earliest))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void returnEarly_otherUsersWindow_returns403Envelope() throws Exception {
        authenticate();
        given(availability.returnEarly(eq(hostId), eq(windowId), any()))
                .willThrow(ApiException.forbidden("NOT_YOUR_WINDOW",
                        "This share belongs to another account."));

        mvc.perform(post("/api/v1/availability/" + windowId + "/return-early")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(returnEarlyJson("2030-01-01T18:00:00Z")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_WINDOW"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }
}
