package com.spotshare.availability.commute;

import java.time.LocalTime;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import com.spotshare.auth.AuthenticatedUser;
import com.spotshare.auth.JwtAuthenticationFilter;
import com.spotshare.auth.SecurityConfig;
import com.spotshare.availability.commute.dto.CommuteScheduleDto;
import com.spotshare.common.ApiException;
import com.spotshare.config.AppProperties;
import com.spotshare.user.Role;

import jakarta.servlet.FilterChain;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice for commute mode: Bean Validation, the JWT security chain,
 * and the error envelope — no database. Service logic is covered by
 * {@link CommuteServiceTest}.
 */
@WebMvcTest(CommuteController.class)
@Import(SecurityConfig.class)
class CommuteWebSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CommuteService commutes;

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
    private final UUID scheduleId = UUID.randomUUID();

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

    private CommuteScheduleDto dto(boolean active) {
        OffsetDateTime now = OffsetDateTime.now();
        return new CommuteScheduleDto(scheduleId, spaceId, 0,
                LocalTime.of(9, 0), LocalTime.of(17, 0), 300,
                "America/Chicago", active, now);
    }

    private String createJson() {
        return """
                {"dayOfWeek":0,"startTime":"09:00","endTime":"17:00",
                 "hourlyRateCents":300,"timezone":"America/Chicago"}
                """;
    }

    @Test
    void create_withoutToken_returns401() throws Exception {
        mvc.perform(post("/api/v1/spaces/" + spaceId + "/commute-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void create_happyPath_returns201WithSchedule() throws Exception {
        authenticate();
        given(commutes.createSchedule(eq(hostId), eq(spaceId), any()))
                .willReturn(dto(true));

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/commute-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(scheduleId.toString()))
                .andExpect(jsonPath("$.dayOfWeek").value(0))
                .andExpect(jsonPath("$.startTime", startsWith("09:00")))
                .andExpect(jsonPath("$.hourlyRateCents").value(300))
                .andExpect(jsonPath("$.timezone").value("America/Chicago"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void create_invalidDayOfWeek_returns400() throws Exception {
        authenticate();

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/commute-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":7,"startTime":"09:00","endTime":"17:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void create_missingTimes_returns400() throws Exception {
        authenticate();

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/commute-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayOfWeek\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void create_strangersSpace_returns403Envelope() throws Exception {
        authenticate();
        given(commutes.createSchedule(eq(hostId), eq(spaceId), any()))
                .willThrow(ApiException.forbidden("NOT_YOUR_SPACE",
                        "This parking space belongs to another account."));

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/commute-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SPACE"));
    }

    @Test
    void create_duplicateWeekday_propagates409() throws Exception {
        authenticate();
        given(commutes.createSchedule(eq(hostId), eq(spaceId), any()))
                .willThrow(ApiException.conflict("SCHEDULE_EXISTS", "duplicate"));

        mvc.perform(post("/api/v1/spaces/" + spaceId + "/commute-schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_EXISTS"));
    }

    @Test
    void list_returnsSchedules() throws Exception {
        authenticate();
        given(commutes.listSchedules(hostId, spaceId)).willReturn(List.of(dto(true)));

        mvc.perform(get("/api/v1/spaces/" + spaceId + "/commute-schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(scheduleId.toString()))
                .andExpect(jsonPath("$[0].dayOfWeek").value(0));
    }

    @Test
    void pause_setsActiveFalse() throws Exception {
        authenticate();
        given(commutes.pause(hostId, scheduleId)).willReturn(dto(false));

        mvc.perform(post("/api/v1/commute-schedules/" + scheduleId + "/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void resume_setsActiveTrue() throws Exception {
        authenticate();
        given(commutes.resume(hostId, scheduleId)).willReturn(dto(true));

        mvc.perform(post("/api/v1/commute-schedules/" + scheduleId + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void delete_returns204() throws Exception {
        authenticate();

        mvc.perform(delete("/api/v1/commute-schedules/" + scheduleId))
                .andExpect(status().isNoContent());
    }

    @Test
    void pause_missingSchedule_returns404Envelope() throws Exception {
        authenticate();
        given(commutes.pause(hostId, scheduleId))
                .willThrow(new ApiException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND",
                        "We couldn't find that commute schedule."));

        mvc.perform(post("/api/v1/commute-schedules/" + scheduleId + "/pause"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }
}
