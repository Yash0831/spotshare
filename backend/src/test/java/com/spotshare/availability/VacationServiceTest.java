package com.spotshare.availability;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;

import com.spotshare.availability.dto.AvailabilityWindowDto;
import com.spotshare.availability.dto.VacationRequest;
import com.spotshare.common.ApiException;
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;
import com.spotshare.reservation.ReservationRepository;
import com.spotshare.reservation.ReservationStatus;
import com.spotshare.user.Role;
import com.spotshare.user.User;

/**
 * Vacation mode without a database: the multi-day share window (start and
 * end chosen by the host), its validation matrix, overlap rejection, and
 * the return-early protection applied to vacation windows. Overlap query
 * semantics against real SQL live in
 * {@link AvailabilityIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class VacationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private AvailabilityWindowRepository windows;

    @Mock
    private ParkingSpaceRepository spaces;

    @Mock
    private ReservationRepository reservations;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private AvailabilityService service;

    private User host;
    private User stranger;
    private ParkingSpace space;

    @BeforeEach
    void setUp() {
        service = new AvailabilityService(windows, spaces, reservations, clock);
        host = new User("host@example.com", "hash", "Holly", "Host", null, Role.USER);
        stranger = new User("stranger@example.com", "hash", "Sam", "Stranger", null, Role.USER);
        space = new ParkingSpace(host);
        space.setActive(true);
    }

    private void stubOwnedSpace() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
    }

    private void stubNoOverlapAndSave() {
        given(windows.findOverlapping(any(), any(), any())).willReturn(List.of());
        given(windows.save(any(AvailabilityWindow.class)))
                .willAnswer((Answer<AvailabilityWindow>) inv -> inv.getArgument(0));
    }

    private VacationRequest vacation(Instant start, Instant end, Integer rateCents) {
        return new VacationRequest(
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC),
                rateCents);
    }

    private static void assertApiException(Throwable t, HttpStatus status, String code) {
        assertThat(t).isInstanceOf(ApiException.class);
        ApiException ex = (ApiException) t;
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getCode()).isEqualTo(code);
    }

    @Test
    void vacation_multiDay_happyPath_sourceVacation() {
        stubOwnedSpace();
        stubNoOverlapAndSave();
        Instant start = NOW.plusSeconds(48 * 3600);   // Friday 12:00
        Instant end = NOW.plusSeconds(75 * 3600);     // Monday 15:00

        AvailabilityWindowDto dto =
                service.vacation(host.getId(), space.getId(), vacation(start, end, 500));

        ArgumentCaptor<AvailabilityWindow> saved = ArgumentCaptor.forClass(AvailabilityWindow.class);
        verify(windows).save(saved.capture());
        AvailabilityWindow window = saved.getValue();
        assertThat(window.getSource()).isEqualTo(WindowSource.VACATION);
        assertThat(window.getStartsAt())
                .isEqualTo(OffsetDateTime.ofInstant(start, ZoneOffset.UTC));
        assertThat(window.getEndsAt())
                .isEqualTo(OffsetDateTime.ofInstant(end, ZoneOffset.UTC));
        assertThat(window.getHourlyRateCents()).isEqualTo(500);
        assertThat(dto.source()).isEqualTo(WindowSource.VACATION);
        assertThat(dto.hourlyRateCents()).isEqualTo(500);
    }

    @Test
    void vacation_free_happyPath() {
        stubOwnedSpace();
        stubNoOverlapAndSave();

        AvailabilityWindowDto dto = service.vacation(host.getId(), space.getId(),
                vacation(NOW.plusSeconds(3600), NOW.plusSeconds(48 * 3600), null));

        assertThat(dto.hourlyRateCents()).isNull();
        assertThat(dto.source()).isEqualTo(WindowSource.VACATION);
    }

    @Test
    void vacation_startInPast_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.vacation(host.getId(), space.getId(),
                vacation(NOW.minusSeconds(3600), NOW.plusSeconds(48 * 3600), null)))
                .satisfies(t -> assertApiException(t,
                        HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_START_TIME"));
        verify(windows, never()).save(any());
    }

    @Test
    void vacation_endBeforeStart_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.vacation(host.getId(), space.getId(),
                vacation(NOW.plusSeconds(48 * 3600), NOW.plusSeconds(24 * 3600), null)))
                .satisfies(t -> assertApiException(t,
                        HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_END_TIME"));
        verify(windows, never()).save(any());
    }

    @Test
    void vacation_endEqualToStart_rejected() {
        stubOwnedSpace();
        Instant at = NOW.plusSeconds(24 * 3600);

        assertThatThrownBy(() -> service.vacation(host.getId(), space.getId(),
                vacation(at, at, null)))
                .satisfies(t -> assertApiException(t,
                        HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_END_TIME"));
        verify(windows, never()).save(any());
    }

    @Test
    void vacation_shorterThanMinimum_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.vacation(host.getId(), space.getId(),
                vacation(NOW.plusSeconds(3600), NOW.plusSeconds(3600 + 29 * 60), null)))
                .satisfies(t -> assertApiException(t,
                        HttpStatus.UNPROCESSABLE_ENTITY, "WINDOW_TOO_SHORT"));
        verify(windows, never()).save(any());
    }

    @Test
    void vacation_invalidPrice_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.vacation(host.getId(), space.getId(),
                vacation(NOW.plusSeconds(3600), NOW.plusSeconds(48 * 3600), 0)))
                .satisfies(t -> assertApiException(t,
                        HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRICE"));
        assertThatThrownBy(() -> service.vacation(host.getId(), space.getId(),
                vacation(NOW.plusSeconds(3600), NOW.plusSeconds(48 * 3600), 10_001)))
                .satisfies(t -> assertApiException(t,
                        HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRICE"));
        verify(windows, never()).save(any());
    }

    @Test
    void vacation_overlappingWindow_rejected() {
        stubOwnedSpace();
        AvailabilityWindow existing = new AvailabilityWindow(space,
                OffsetDateTime.ofInstant(NOW.plusSeconds(24 * 3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(60 * 3600), ZoneOffset.UTC),
                WindowSource.MANUAL, null,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        given(windows.findOverlapping(any(), any(), any())).willReturn(List.of(existing));

        assertThatThrownBy(() -> service.vacation(host.getId(), space.getId(),
                vacation(NOW.plusSeconds(48 * 3600), NOW.plusSeconds(72 * 3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.CONFLICT, "OVERLAPPING_WINDOW"));
        verify(windows, never()).save(any());
    }

    @Test
    void vacation_adjacentToExistingWindow_allowed() {
        stubOwnedSpace();
        stubNoOverlapAndSave();
        // Ends exactly when the existing share starts — half-open, no overlap.
        Instant start = NOW.plusSeconds(24 * 3600);
        Instant end = NOW.plusSeconds(48 * 3600);

        AvailabilityWindowDto dto =
                service.vacation(host.getId(), space.getId(), vacation(start, end, null));

        assertThat(dto.startsAt())
                .isEqualTo(OffsetDateTime.ofInstant(start, ZoneOffset.UTC));
    }

    @Test
    void vacation_otherAccount_forbidden() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));

        assertThatThrownBy(() -> service.vacation(stranger.getId(), space.getId(),
                vacation(NOW.plusSeconds(3600), NOW.plusSeconds(48 * 3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.FORBIDDEN, "NOT_YOUR_SPACE"));
        verify(windows, never()).save(any());
    }

    @Test
    void vacation_unknownSpace_notFound() {
        UUID unknown = UUID.randomUUID();
        given(spaces.findById(unknown)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.vacation(host.getId(), unknown,
                vacation(NOW.plusSeconds(3600), NOW.plusSeconds(48 * 3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND"));
    }

    @Test
    void vacation_inactiveSpace_gone() {
        space.setActive(false);
        stubOwnedSpace();

        assertThatThrownBy(() -> service.vacation(host.getId(), space.getId(),
                vacation(NOW.plusSeconds(3600), NOW.plusSeconds(48 * 3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.GONE, "SPACE_INACTIVE"));
        verify(windows, never()).save(any());
    }

    @Test
    void returnEarly_onVacationWindow_confirmedReservationBlocks() {
        // Ending a vacation early is the same return-early path as a manual
        // share: a driver parked past the new return blocks it, with the
        // exact earliest return in the error details.
        OffsetDateTime startsAt = OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.ofInstant(NOW.plusSeconds(72 * 3600), ZoneOffset.UTC);
        AvailabilityWindow vacation = new AvailabilityWindow(space, startsAt, endsAt,
                WindowSource.VACATION, 300, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        given(windows.findById(vacation.getId())).willReturn(Optional.of(vacation));
        OffsetDateTime driverDeparture =
                OffsetDateTime.ofInstant(NOW.plusSeconds(90 * 60), ZoneOffset.UTC);
        given(reservations.findLatestConfirmedDepartureInWindow(
                eq(space.getId()),
                eq(ReservationStatus.CONFIRMED),
                eq(startsAt),
                eq(endsAt)))
                .willReturn(Optional.of(driverDeparture));

        assertThatThrownBy(() -> service.returnEarly(host.getId(), vacation.getId(),
                OffsetDateTime.ofInstant(NOW.plusSeconds(60 * 60), ZoneOffset.UTC)))
                .satisfies(t -> {
                    assertApiException(t, HttpStatus.UNPROCESSABLE_ENTITY,
                            "RETURN_BLOCKED_BY_RESERVATION");
                    ApiException ex = (ApiException) t;
                    assertThat(ex.getDetails())
                            .containsEntry("earliestReturnTime", driverDeparture.toString());
                });
        verify(windows, never()).save(any());
    }

    @Test
    void returnEarly_onVacationWindow_noReservation_shrinks() {
        // "End vacation now": the window shrinks to now and the reservation
        // protection simply finds nothing to protect.
        OffsetDateTime startsAt = OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC);
        OffsetDateTime endsAt = OffsetDateTime.ofInstant(NOW.plusSeconds(72 * 3600), ZoneOffset.UTC);
        AvailabilityWindow vacation = new AvailabilityWindow(space, startsAt, endsAt,
                WindowSource.VACATION, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        given(windows.findById(vacation.getId())).willReturn(Optional.of(vacation));
        given(reservations.findLatestConfirmedDepartureInWindow(
                any(), any(), any(), any())).willReturn(Optional.empty());
        given(windows.save(any(AvailabilityWindow.class)))
                .willAnswer((Answer<AvailabilityWindow>) inv -> inv.getArgument(0));

        OffsetDateTime newReturn = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        AvailabilityWindowDto dto = service.returnEarly(host.getId(), vacation.getId(), newReturn);

        assertThat(dto.endsAt()).isEqualTo(newReturn);
        verify(windows).save(any(AvailabilityWindow.class));
    }
}
