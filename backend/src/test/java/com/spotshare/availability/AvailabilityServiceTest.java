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
import com.spotshare.availability.dto.ShareRequest;
import com.spotshare.common.ApiException;
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;
import com.spotshare.user.Role;
import com.spotshare.user.User;

/**
 * AvailabilityService behavior without a database: share rules (future return
 * time, minimum duration, price range, overlap rejection), owner-only access,
 * idempotent removal, and derived expiry against a fixed clock. The real
 * overlap query semantics and the migration are covered by
 * {@link AvailabilityIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private AvailabilityWindowRepository windows;

    @Mock
    private ParkingSpaceRepository spaces;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private AvailabilityService service;

    private User host;
    private User stranger;
    private ParkingSpace space;

    @BeforeEach
    void setUp() {
        service = new AvailabilityService(windows, spaces, clock);
        host = new User("host@example.com", "hash", "Holly", "Host", null, Role.USER);
        stranger = new User("stranger@example.com", "hash", "Sam", "Stranger", null, Role.USER);
        space = new ParkingSpace(host);
        space.setActive(true);
    }

    /** The space exists and belongs to the host. */
    private void stubOwnedSpace() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
    }

    /** No overlapping windows, and save() returns what it is given. */
    private void stubNoOverlapAndSave() {
        given(windows.findOverlapping(any(), any(), any())).willReturn(List.of());
        given(windows.save(any(AvailabilityWindow.class)))
                .willAnswer((Answer<AvailabilityWindow>) inv -> inv.getArgument(0));
    }

    private ShareRequest share(Instant returnTime, Integer rateCents) {
        return new ShareRequest(OffsetDateTime.ofInstant(returnTime, ZoneOffset.UTC), rateCents);
    }

    private static void assertApiException(Throwable t, HttpStatus status, String code) {
        assertThat(t).isInstanceOf(ApiException.class);
        ApiException ex = (ApiException) t;
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getCode()).isEqualTo(code);
    }

    @Test
    void share_free_happyPath() {
        stubOwnedSpace();
        stubNoOverlapAndSave();
        OffsetDateTime returnTime = OffsetDateTime.ofInstant(NOW.plusSeconds(2 * 3600), ZoneOffset.UTC);

        AvailabilityWindowDto dto = service.share(host.getId(), space.getId(),
                new ShareRequest(returnTime, null));

        ArgumentCaptor<AvailabilityWindow> saved = ArgumentCaptor.forClass(AvailabilityWindow.class);
        verify(windows).save(saved.capture());
        AvailabilityWindow window = saved.getValue();
        assertThat(window.getStartsAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(window.getEndsAt()).isEqualTo(returnTime);
        assertThat(window.getSource()).isEqualTo(WindowSource.MANUAL);
        assertThat(window.getHourlyRateCents()).isNull();
        assertThat(dto.live()).isTrue();
        assertThat(dto.spaceId()).isEqualTo(space.getId());
        assertThat(dto.hourlyRateCents()).isNull();
    }

    @Test
    void share_paid_persistsCents() {
        stubOwnedSpace();
        stubNoOverlapAndSave();

        AvailabilityWindowDto dto = service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(3 * 3600), 300));

        ArgumentCaptor<AvailabilityWindow> saved = ArgumentCaptor.forClass(AvailabilityWindow.class);
        verify(windows).save(saved.capture());
        assertThat(saved.getValue().getHourlyRateCents()).isEqualTo(300);
        assertThat(dto.hourlyRateCents()).isEqualTo(300);
    }

    @Test
    void share_maxRate_boundaryAllowed() {
        stubOwnedSpace();
        stubNoOverlapAndSave();

        service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(2 * 3600), AvailabilityService.MAX_HOURLY_RATE_CENTS));

        verify(windows).save(any(AvailabilityWindow.class));
    }

    @Test
    void share_exactlyThirtyMinutes_allowed() {
        stubOwnedSpace();
        stubNoOverlapAndSave();

        service.share(host.getId(), space.getId(), share(NOW.plusSeconds(30 * 60), null));

        verify(windows).save(any(AvailabilityWindow.class));
    }

    @Test
    void share_pastReturnTime_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW.minusSeconds(3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_RETURN_TIME"));
        verify(windows, never()).save(any());
    }

    @Test
    void share_returnTimeNow_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW, null)))
                .satisfies(t -> assertApiException(t, HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_RETURN_TIME"));
    }

    @Test
    void share_underThirtyMinutes_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(29 * 60), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.UNPROCESSABLE_ENTITY,
                        "WINDOW_TOO_SHORT"));
        verify(windows, never()).save(any());
    }

    @Test
    void share_zeroOrNegativeRate_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(2 * 3600), 0)))
                .satisfies(t -> assertApiException(t, HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_PRICE"));
        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(2 * 3600), -100)))
                .satisfies(t -> assertApiException(t, HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_PRICE"));
        verify(windows, never()).save(any());
    }

    @Test
    void share_overCapRate_rejected() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(2 * 3600), AvailabilityService.MAX_HOURLY_RATE_CENTS + 1)))
                .satisfies(t -> assertApiException(t, HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_PRICE"));
        verify(windows, never()).save(any());
    }

    @Test
    void share_overlappingWindow_rejected() {
        stubOwnedSpace();
        AvailabilityWindow existing = new AvailabilityWindow(space,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(2 * 3600), ZoneOffset.UTC),
                WindowSource.MANUAL, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        given(windows.findOverlapping(any(), any(), any())).willReturn(List.of(existing));

        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(3 * 3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.CONFLICT, "OVERLAPPING_WINDOW"));
        verify(windows, never()).save(any());
    }

    @Test
    void share_checksOverlapForExactNewPeriod() {
        stubOwnedSpace();
        stubNoOverlapAndSave();
        OffsetDateTime returnTime = OffsetDateTime.ofInstant(NOW.plusSeconds(2 * 3600), ZoneOffset.UTC);

        service.share(host.getId(), space.getId(), new ShareRequest(returnTime, null));

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(windows).findOverlapping(any(), start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(end.getValue()).isEqualTo(returnTime);
    }

    @Test
    void share_unknownSpace_returns404() {
        given(spaces.findById(space.getId())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(2 * 3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND"));
    }

    @Test
    void share_otherUsersSpace_returns403() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));

        assertThatThrownBy(() -> service.share(stranger.getId(), space.getId(),
                share(NOW.plusSeconds(2 * 3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.FORBIDDEN, "NOT_YOUR_SPACE"));
        verify(windows, never()).save(any());
    }

    @Test
    void share_inactiveSpace_returns410() {
        space.setActive(false);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));

        assertThatThrownBy(() -> service.share(host.getId(), space.getId(),
                share(NOW.plusSeconds(2 * 3600), null)))
                .satisfies(t -> assertApiException(t, HttpStatus.GONE, "SPACE_INACTIVE"));
        verify(windows, never()).save(any());
    }

    @Test
    void removeWindow_notStarted_deletes() {
        AvailabilityWindow window = new AvailabilityWindow(space,
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(3 * 3600), ZoneOffset.UTC),
                WindowSource.MANUAL, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        given(windows.findById(window.getId())).willReturn(Optional.of(window));

        service.removeWindow(host.getId(), window.getId());

        verify(windows).delete(window);
    }

    @Test
    void removeWindow_unknownId_isNoOpSuccess() {
        UUID unknown = UUID.randomUUID();
        given(windows.findById(unknown)).willReturn(Optional.empty());

        // No throw — idempotent, e.g. a double-tap retry.
        service.removeWindow(host.getId(), unknown);

        verify(windows, never()).delete(any());
    }

    @Test
    void removeWindow_alreadyStarted_rejected() {
        AvailabilityWindow window = new AvailabilityWindow(space,
                OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                WindowSource.MANUAL, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        given(windows.findById(window.getId())).willReturn(Optional.of(window));

        assertThatThrownBy(() -> service.removeWindow(host.getId(), window.getId()))
                .satisfies(t -> assertApiException(t, HttpStatus.UNPROCESSABLE_ENTITY,
                        "WINDOW_ALREADY_STARTED"));
        verify(windows, never()).delete(any());
    }

    @Test
    void removeWindow_otherAccount_rejected() {
        AvailabilityWindow window = new AvailabilityWindow(space,
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(3 * 3600), ZoneOffset.UTC),
                WindowSource.MANUAL, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        given(windows.findById(window.getId())).willReturn(Optional.of(window));

        assertThatThrownBy(() -> service.removeWindow(stranger.getId(), window.getId()))
                .satisfies(t -> assertApiException(t, HttpStatus.FORBIDDEN, "NOT_YOUR_WINDOW"));
        verify(windows, never()).delete(any());
    }

    @Test
    void expiry_isDerivedFromTimestamps() {
        // A window whose end is in the past is simply not live — nothing is
        // "closed", there is no status to flip.
        AvailabilityWindow ended = new AvailabilityWindow(space,
                OffsetDateTime.ofInstant(NOW.minusSeconds(3 * 3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC),
                WindowSource.MANUAL, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        AvailabilityWindow live = new AvailabilityWindow(space,
                OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
                WindowSource.MANUAL, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(ended.isLive(now)).isFalse();
        assertThat(ended.hasStarted(now)).isTrue();
        assertThat(live.isLive(now)).isTrue();
        assertThat(live.isLive(now.plusSeconds(3600))).isFalse();
        assertThat(live.isLive(now.minusSeconds(3600))).isTrue();
    }
}
