package com.spotshare.availability.commute;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
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

import com.spotshare.availability.AvailabilityWindow;
import com.spotshare.availability.AvailabilityWindowRepository;
import com.spotshare.availability.WindowSource;
import com.spotshare.availability.commute.dto.CommuteScheduleDto;
import com.spotshare.availability.commute.dto.CreateCommuteScheduleRequest;
import com.spotshare.common.ApiException;
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;
import com.spotshare.reservation.ReservationRepository;
import com.spotshare.user.Role;
import com.spotshare.user.User;

/**
 * CommuteService behavior without a database: schedule validation, timezone
 * handling with a fixed clock, idempotent materialization, pause/delete
 * cleanup that never touches reserved windows, and host-only access. The
 * real queries and the migration are additionally exercised against
 * PostgreSQL via psql.
 */
@ExtendWith(MockitoExtension.class)
class CommuteServiceTest {

    /** A Sunday — the materializer should find the next Mondays. */
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Mock
    private CommuteScheduleRepository schedules;

    @Mock
    private AvailabilityWindowRepository windows;

    @Mock
    private ParkingSpaceRepository spaces;

    @Mock
    private ReservationRepository reservations;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private CommuteService service;

    private User host;
    private User stranger;
    private ParkingSpace space;

    @BeforeEach
    void setUp() {
        service = new CommuteService(schedules, windows, spaces, reservations, clock);
        host = new User("host@example.com", "hash", "Holly", "Host", null, Role.USER);
        stranger = new User("stranger@example.com", "hash", "Sam", "Stranger", null, Role.USER);
        space = new ParkingSpace(host);
        space.setActive(true);
    }

    private void stubOwnedSpace() {
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
    }

    private CreateCommuteScheduleRequest req(int dayOfWeek, String start, String end,
                                             Integer rateCents, String timezone) {
        return new CreateCommuteScheduleRequest(dayOfWeek,
                LocalTime.parse(start), LocalTime.parse(end), rateCents, timezone);
    }

    /**
     * Stubs a clean materialization run: the weekday slot is free, no manual
     * share overlaps, and {@code save} returns what it is given.
     */
    private void stubCleanMaterialization(CommuteSchedule schedule) {
        given(schedules.findBySpaceIdAndActiveTrueOrderByDayOfWeekAsc(space.getId()))
                .willReturn(List.of(schedule));
        given(windows.existsBySpaceIdAndStartsAtAndEndsAtAndSource(
                eq(space.getId()), any(), any(), eq(WindowSource.COMMUTE)))
                .willReturn(false);
        given(windows.findOverlapping(eq(space.getId()), any(), any()))
                .willReturn(List.of());
        given(windows.save(any(AvailabilityWindow.class)))
                .willAnswer((Answer<AvailabilityWindow>) inv -> inv.getArgument(0));
        given(schedules.save(any(CommuteSchedule.class)))
                .willAnswer((Answer<CommuteSchedule>) inv -> inv.getArgument(0));
    }

    @Test
    void createSchedule_materializesNextMondaysAtLocalTime() {
        stubOwnedSpace();
        given(schedules.existsBySpaceIdAndDayOfWeek(space.getId(), 0)).willReturn(false);
        CommuteSchedule schedule = new CommuteSchedule(space, 0,
                LocalTime.of(9, 0), LocalTime.of(17, 0), 300, "America/Chicago",
                OffsetDateTime.now(clock));
        stubCleanMaterialization(schedule);

        CommuteScheduleDto dto = service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "17:00", 300, "America/Chicago"));

        assertThat(dto.dayOfWeek()).isZero();
        assertThat(dto.timezone()).isEqualTo("America/Chicago");
        assertThat(dto.active()).isTrue();

        ArgumentCaptor<AvailabilityWindow> captor = ArgumentCaptor.forClass(AvailabilityWindow.class);
        verify(windows, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<AvailabilityWindow> created = captor.getAllValues();
        // 2026-09-27 is a Sunday; within 14 days the Mondays are 09-28 and 10-05.
        assertThat(created).hasSize(2);
        assertThat(created.get(0).getStartsAt().toInstant())
                .isEqualTo(Instant.parse("2026-09-28T14:00:00Z")); // 09:00 CDT = 14:00Z
        assertThat(created.get(0).getEndsAt().toInstant())
                .isEqualTo(Instant.parse("2026-09-28T22:00:00Z")); // 17:00 CDT = 22:00Z
        assertThat(created.get(1).getStartsAt().toInstant())
                .isEqualTo(Instant.parse("2026-10-05T14:00:00Z"));
        assertThat(created.stream().map(AvailabilityWindow::getSource))
                .containsOnly(WindowSource.COMMUTE);
        assertThat(created.stream().map(AvailabilityWindow::getHourlyRateCents))
                .containsOnly(300);
    }

    @Test
    void createSchedule_isIdempotentWhenWindowsAlreadyExist() {
        stubOwnedSpace();
        given(schedules.existsBySpaceIdAndDayOfWeek(space.getId(), 0)).willReturn(false);
        CommuteSchedule schedule = new CommuteSchedule(space, 0,
                LocalTime.of(9, 0), LocalTime.of(17, 0), null, "UTC",
                OffsetDateTime.now(clock));
        given(schedules.findBySpaceIdAndActiveTrueOrderByDayOfWeekAsc(space.getId()))
                .willReturn(List.of(schedule));
        given(windows.existsBySpaceIdAndStartsAtAndEndsAtAndSource(
                eq(space.getId()), any(), any(), eq(WindowSource.COMMUTE)))
                .willReturn(true); // already materialized
        given(schedules.save(any(CommuteSchedule.class)))
                .willAnswer((Answer<CommuteSchedule>) inv -> inv.getArgument(0));

        service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "17:00", null, "UTC"));

        verify(windows, never()).save(any(AvailabilityWindow.class));
    }

    @Test
    void createSchedule_skipsPeriodsCoveredByManualShare() {
        stubOwnedSpace();
        given(schedules.existsBySpaceIdAndDayOfWeek(space.getId(), 0)).willReturn(false);
        CommuteSchedule schedule = new CommuteSchedule(space, 0,
                LocalTime.of(9, 0), LocalTime.of(17, 0), null, "UTC",
                OffsetDateTime.now(clock));
        given(schedules.findBySpaceIdAndActiveTrueOrderByDayOfWeekAsc(space.getId()))
                .willReturn(List.of(schedule));
        given(windows.existsBySpaceIdAndStartsAtAndEndsAtAndSource(
                eq(space.getId()), any(), any(), eq(WindowSource.COMMUTE)))
                .willReturn(false);
        // A manual share already covers every candidate period.
        given(windows.findOverlapping(eq(space.getId()), any(), any()))
                .willReturn(List.of(commuteWindow(
                        Instant.parse("2026-09-28T09:00:00Z"),
                        Instant.parse("2026-09-28T17:00:00Z"))));
        given(schedules.save(any(CommuteSchedule.class)))
                .willAnswer((Answer<CommuteSchedule>) inv -> inv.getArgument(0));

        service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "17:00", null, "UTC"));

        verify(windows, never()).save(any(AvailabilityWindow.class));
    }

    @Test
    void createSchedule_rejectsEndBeforeStart() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.createSchedule(host.getId(), space.getId(),
                req(0, "17:00", "09:00", null, "UTC")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.getCode()).isEqualTo("INVALID_SCHEDULE");
                });
    }

    @Test
    void createSchedule_rejectsWindowUnder30Minutes() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "09:20", null, "UTC")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("SCHEDULE_TOO_SHORT"));
    }

    @Test
    void createSchedule_rejectsBadRate() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "17:00", 0, "UTC")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("INVALID_PRICE"));
        assertThatThrownBy(() -> service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "17:00", 10_001, "UTC")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("INVALID_PRICE"));
    }

    @Test
    void createSchedule_rejectsBadTimezone() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "17:00", null, "Mars/Olympus")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("INVALID_TIMEZONE"));
    }

    @Test
    void createSchedule_rejectsDuplicateWeekday() {
        stubOwnedSpace();
        given(schedules.existsBySpaceIdAndDayOfWeek(space.getId(), 0)).willReturn(true);

        assertThatThrownBy(() -> service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "17:00", null, "UTC")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("SCHEDULE_EXISTS");
                    assertThat(api.getMessage()).contains("Monday");
                });
    }

    @Test
    void createSchedule_requiresOwner() {
        stubOwnedSpace();

        assertThatThrownBy(() -> service.createSchedule(stranger.getId(), space.getId(),
                req(0, "09:00", "17:00", null, "UTC")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("NOT_YOUR_SPACE"));
    }

    @Test
    void createSchedule_rejectsInactiveSpace() {
        space.setActive(false);
        stubOwnedSpace();

        assertThatThrownBy(() -> service.createSchedule(host.getId(), space.getId(),
                req(0, "09:00", "17:00", null, "UTC")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(api.getCode()).isEqualTo("SPACE_INACTIVE");
                });
    }

    @Test
    void createSchedule_defaultsBlankTimezoneToUtc() {
        stubOwnedSpace();
        given(schedules.existsBySpaceIdAndDayOfWeek(space.getId(), 2)).willReturn(false);
        CommuteSchedule schedule = new CommuteSchedule(space, 2,
                LocalTime.of(9, 0), LocalTime.of(17, 0), null, "UTC",
                OffsetDateTime.now(clock));
        stubCleanMaterialization(schedule);

        CommuteScheduleDto dto = service.createSchedule(host.getId(), space.getId(),
                req(2, "09:00", "17:00", null, null));

        assertThat(dto.timezone()).isEqualTo("UTC");
    }

    private CommuteSchedule activeSchedule() {
        CommuteSchedule schedule = new CommuteSchedule(space, 0,
                LocalTime.of(9, 0), LocalTime.of(17, 0), 300, "UTC",
                OffsetDateTime.now(clock));
        given(schedules.findById(schedule.getId())).willReturn(Optional.of(schedule));
        return schedule;
    }

    private AvailabilityWindow commuteWindow(Instant start, Instant end) {
        return new AvailabilityWindow(space,
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(end, ZoneOffset.UTC),
                WindowSource.COMMUTE, 300, OffsetDateTime.now(clock));
    }

    @Test
    void pause_removesFutureUnreservedWindowsButKeepsReservedOnes() {
        CommuteSchedule schedule = activeSchedule();
        AvailabilityWindow unreserved = commuteWindow(
                Instant.parse("2026-09-28T09:00:00Z"), Instant.parse("2026-09-28T17:00:00Z"));
        AvailabilityWindow reserved = commuteWindow(
                Instant.parse("2026-10-05T09:00:00Z"), Instant.parse("2026-10-05T17:00:00Z"));
        given(windows.findBySpaceIdAndSourceAndStartsAtAfter(
                eq(space.getId()), eq(WindowSource.COMMUTE), any()))
                .willReturn(List.of(unreserved, reserved));
        // Only the October window has a confirmed reservation; the general
        // stub covers the unreserved one (later, more specific stub wins).
        given(reservations.existsConfirmedOverlap(eq(space.getId()), any(), any()))
                .willReturn(false);
        given(reservations.existsConfirmedOverlap(eq(space.getId()),
                eq(reserved.getStartsAt()), eq(reserved.getEndsAt())))
                .willReturn(true);
        // After pausing, no active schedules remain for the space.
        given(schedules.findBySpaceIdAndActiveTrueOrderByDayOfWeekAsc(space.getId()))
                .willReturn(List.of());

        CommuteScheduleDto dto = service.pause(host.getId(), schedule.getId());

        assertThat(dto.active()).isFalse();
        verify(windows).delete(unreserved);
        verify(windows, never()).delete(reserved);
        // Pausing must not touch the reservations themselves.
        verify(reservations, never()).save(any());
        verify(reservations, never()).delete(any());
    }

    @Test
    void resume_reactivatesAndMaterializesImmediately() {
        CommuteSchedule schedule = activeSchedule();
        schedule.setActive(false, OffsetDateTime.now(clock));
        stubCleanMaterialization(schedule);

        CommuteScheduleDto dto = service.resume(host.getId(), schedule.getId());

        assertThat(dto.active()).isTrue();
        verify(windows, org.mockito.Mockito.atLeastOnce()).save(any(AvailabilityWindow.class));
    }

    @Test
    void delete_removesScheduleAndCleansUpFutureWindows() {
        CommuteSchedule schedule = activeSchedule();
        given(windows.findBySpaceIdAndSourceAndStartsAtAfter(
                eq(space.getId()), eq(WindowSource.COMMUTE), any()))
                .willReturn(List.of());
        given(schedules.findBySpaceIdAndActiveTrueOrderByDayOfWeekAsc(space.getId()))
                .willReturn(List.of());

        service.deleteSchedule(host.getId(), schedule.getId());

        verify(schedules).delete(schedule);
    }

    @Test
    void scheduleActions_rejectStranger() {
        CommuteSchedule schedule = activeSchedule();

        assertThatThrownBy(() -> service.pause(stranger.getId(), schedule.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("NOT_YOUR_SPACE"));
        assertThatThrownBy(() -> service.resume(stranger.getId(), schedule.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.deleteSchedule(stranger.getId(), schedule.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void scheduleActions_rejectMissingSchedule() {
        given(schedules.findById(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.pause(host.getId(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getCode()).isEqualTo("SCHEDULE_NOT_FOUND");
                });
    }

    @Test
    void materializeSpace_skipsInactiveAndMissingSpaces() {
        space.setActive(false);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));

        service.materializeSpace(space.getId());
        verify(schedules, never()).findBySpaceIdAndActiveTrueOrderByDayOfWeekAsc(any());

        given(spaces.findById(space.getId())).willReturn(Optional.empty());
        service.materializeSpace(space.getId());
        verify(windows, never()).save(any(AvailabilityWindow.class));
    }

    @Test
    void listSchedules_returnsWeekdayOrder() {
        stubOwnedSpace();
        CommuteSchedule friday = new CommuteSchedule(space, 4,
                LocalTime.of(9, 0), LocalTime.of(17, 0), null, "UTC",
                OffsetDateTime.now(clock));
        CommuteSchedule monday = new CommuteSchedule(space, 0,
                LocalTime.of(9, 0), LocalTime.of(17, 0), null, "UTC",
                OffsetDateTime.now(clock));
        given(schedules.findBySpaceIdOrderByDayOfWeekAsc(space.getId()))
                .willReturn(List.of(monday, friday));

        List<CommuteScheduleDto> list = service.listSchedules(host.getId(), space.getId());

        assertThat(list).extracting(CommuteScheduleDto::dayOfWeek).containsExactly(0, 4);
    }
}
