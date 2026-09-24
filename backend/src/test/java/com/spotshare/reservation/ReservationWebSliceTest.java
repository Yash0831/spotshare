package com.spotshare.reservation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

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
import com.spotshare.common.ApiException;
import com.spotshare.config.AppProperties;
import com.spotshare.parking.ParkingType;
import com.spotshare.reservation.dto.HostArrivalDto;
import com.spotshare.reservation.dto.ReservationDetailDto;
import com.spotshare.reservation.dto.ReservationDto;
import com.spotshare.user.Role;

import jakarta.servlet.FilterChain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice for reservations: Bean Validation, the Idempotency-Key
 * header, the 201-vs-200 distinction, the JWT security chain, and the error
 * envelope — no database.
 */
@WebMvcTest(ReservationController.class)
@Import(SecurityConfig.class)
class ReservationWebSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ReservationService reservations;

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

    private final UUID driverId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        doAnswer(inv -> {
            var chain = (FilterChain) inv.getArgument(2);
            var auth = new UsernamePasswordAuthenticationToken(
                    new AuthenticatedUser(driverId, "driver@example.com", Role.USER),
                    null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());
    }

    private String bookingBody() {
        return """
                {"spaceId":"%s","arrival":"2026-09-24T14:00:00Z","departure":"2026-09-24T16:00:00Z"}
                """.formatted(spaceId);
    }

    private ReservationDto dto() {
        OffsetDateTime arrival = OffsetDateTime.of(2026, 9, 24, 14, 0, 0, 0, ZoneOffset.UTC);
        return new ReservationDto(reservationId, spaceId, "West Loop", "Chicago", "IL",
                ParkingType.DRIVEWAY, arrival, arrival.plusHours(2), 300, 600,
                ReservationStatus.CONFIRMED, "SP-K84D2", null, arrival);
    }

    private ReservationDetailDto detailDto() {
        OffsetDateTime arrival = OffsetDateTime.of(2026, 9, 24, 14, 0, 0, 0, ZoneOffset.UTC);
        return new ReservationDetailDto(reservationId, spaceId, "West Loop", "Chicago", "IL",
                ParkingType.DRIVEWAY, arrival, arrival.plusHours(2), 300, 600,
                ReservationStatus.CONFIRMED, "SP-K84D2", "B17", "123 Private Way", "60606",
                "Gate code 1234", "Holly H.", "Dan D.", arrival);
    }

    @Test
    void freshBookingReturns201AndPassesTheIdempotencyKey() throws Exception {
        given(reservations.create(eq(driverId), any(), eq("key-123")))
                .willReturn(new ReservationService.CreateResult(dto(), true));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-123")
                        .content(bookingBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SP-K84D2"))
                .andExpect(jsonPath("$.totalCents").value(600));

        verify(reservations).create(eq(driverId), any(), eq("key-123"));
    }

    @Test
    void idempotentReplayReturns200() throws Exception {
        given(reservations.create(eq(driverId), any(), eq("key-123")))
                .willReturn(new ReservationService.CreateResult(dto(), false));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-123")
                        .content(bookingBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SP-K84D2"));
    }

    @Test
    void bookingWorksWithoutAnIdempotencyKey() throws Exception {
        given(reservations.create(eq(driverId), any(), eq(null)))
                .willReturn(new ReservationService.CreateResult(dto(), true));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void bookingValidatesTheBody() throws Exception {
        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"arrival":"2026-09-24T14:00:00Z","departure":"2026-09-24T16:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void doubleBookingMapsToTheFriendly409() throws Exception {
        given(reservations.create(eq(driverId), any(), any()))
                .willThrow(ApiException.conflict("SPACE_JUST_RESERVED",
                        "This space was just reserved. Please choose another nearby space."));

        mvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPACE_JUST_RESERVED"))
                .andExpect(jsonPath("$.message").value(
                        "This space was just reserved. Please choose another nearby space."));
    }

    @Test
    void mineListsReservations() throws Exception {
        given(reservations.mine(driverId, null)).willReturn(List.of(dto()));

        mvc.perform(get("/api/v1/reservations/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("SP-K84D2"))
                // The summary DTO never carries the exact address.
                .andExpect(jsonPath("$[0].address").doesNotExist());
    }

    @Test
    void detailRevealsTheAddress() throws Exception {
        given(reservations.get(driverId, reservationId)).willReturn(detailDto());

        mvc.perform(get("/api/v1/reservations/" + reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("123 Private Way"))
                .andExpect(jsonPath("$.spaceLabel").value("B17"))
                .andExpect(jsonPath("$.parkingInstructions").value("Gate code 1234"));
    }

    @Test
    void cancelReturnsTheUpdatedReservation() throws Exception {
        ReservationDto cancelled = new ReservationDto(dto().id(), dto().spaceId(), dto().areaLabel(),
                dto().city(), dto().state(), dto().parkingType(), dto().arrival(), dto().departure(),
                dto().hourlyRateCents(), dto().totalCents(), ReservationStatus.CANCELLED,
                dto().code(), CancelledBy.DRIVER, dto().createdAt());
        given(reservations.cancel(driverId, reservationId)).willReturn(cancelled);

        mvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        SecurityContextHolder.clearContext();
        doAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());

        mvc.perform(get("/api/v1/reservations/mine"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void hostArrivalsListsTheDaysReservations() throws Exception {
        OffsetDateTime arrival = OffsetDateTime.of(2026, 9, 24, 14, 0, 0, 0, ZoneOffset.UTC);
        var arrivals = List.of(new HostArrivalDto(reservationId, "SP-K84D2", "Dan D.",
                arrival, arrival.plusHours(2), ReservationStatus.CONFIRMED, null));
        given(reservations.arrivalsForSpace(eq(driverId), eq(spaceId), any())).willReturn(arrivals);

        mvc.perform(get("/api/v1/spaces/" + spaceId + "/reservations?date=2026-09-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("SP-K84D2"))
                .andExpect(jsonPath("$[0].driverName").value("Dan D."))
                // The arrivals view never carries contact details.
                .andExpect(jsonPath("$[0].driverEmail").doesNotExist())
                .andExpect(jsonPath("$[0].address").doesNotExist());
    }

    @Test
    void hostArrivalsRejectedForNonHost() throws Exception {
        given(reservations.arrivalsForSpace(eq(driverId), eq(spaceId), any()))
                .willThrow(ApiException.forbidden("NOT_YOUR_SPACE",
                        "Only the host can view reservations for this space."));

        mvc.perform(get("/api/v1/spaces/" + spaceId + "/reservations"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SPACE"));
    }

    @Test
    void hostArrivalsRejectsABadDate() throws Exception {
        mvc.perform(get("/api/v1/spaces/" + spaceId + "/reservations?date=not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
