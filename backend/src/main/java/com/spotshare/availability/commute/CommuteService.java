package com.spotshare.availability.commute;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spotshare.availability.AvailabilityService;
import com.spotshare.availability.AvailabilityWindow;
import com.spotshare.availability.AvailabilityWindowRepository;
import com.spotshare.availability.WindowSource;
import com.spotshare.availability.commute.dto.CommuteScheduleDto;
import com.spotshare.availability.commute.dto.CreateCommuteScheduleRequest;
import com.spotshare.common.ApiException;
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;
import com.spotshare.reservation.ReservationRepository;

/**
 * Commute mode (spec §7, Phase 8): the host sets a weekly pattern once —
 * "my spot is empty weekdays 9:00–17:00" — and the system materializes real
 * {@code availability_windows} (source=COMMUTE) for the next
 * {@link CommuteMaterializer#DAYS_AHEAD} days. Commute windows are ordinary
 * windows: search shows them, drivers reserve them, return-early applies to
 * them, and the same booking/exclusion rules hold. No parallel concepts.
 *
 * <p>Times are local times-of-day in the schedule's IANA timezone (the host's
 * device timezone at setup), so a 9:00 window stays 9:00 local across
 * daylight-saving changes. One entry per weekday per space — enforced by the
 * unique key, which makes same-weekday overlaps impossible.
 *
 * <p>Every space-scoped operation verifies the caller is the space's host;
 * a different authenticated user gets a 403, never the data.
 */
@Service
public class CommuteService {

    /** "Monday" .. "Sunday" — index 0..6 matches {@code dayOfWeek}. */
    private static final String[] DAY_NAMES = {
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };

    private final CommuteScheduleRepository schedules;
    private final AvailabilityWindowRepository windows;
    private final ParkingSpaceRepository spaces;
    private final ReservationRepository reservations;
    private final Clock clock;

    public CommuteService(CommuteScheduleRepository schedules,
                          AvailabilityWindowRepository windows,
                          ParkingSpaceRepository spaces,
                          ReservationRepository reservations,
                          Clock clock) {
        this.schedules = schedules;
        this.windows = windows;
        this.spaces = spaces;
        this.reservations = reservations;
        this.clock = clock;
    }

    /**
     * Adds one weekly commute entry and materializes its windows immediately,
     * so the host sees the pattern take effect right away (the hourly sweep
     * then keeps it rolled 14 days out).
     */
    @Transactional
    public CommuteScheduleDto createSchedule(UUID hostId, UUID spaceId,
                                             CreateCommuteScheduleRequest req) {
        ParkingSpace space = ownedSpace(hostId, spaceId);
        if (!space.isActive()) {
            throw new ApiException(HttpStatus.GONE, "SPACE_INACTIVE",
                    "This parking space is deactivated. Reactivate it before setting up commute mode.");
        }
        LocalTime start = req.startTime();
        LocalTime end = req.endTime();
        if (!end.isAfter(start)) {
            throw ApiException.unprocessable("INVALID_SCHEDULE",
                    "The end time must be after the start time.");
        }
        if (Duration.between(start, end).toMinutes() < AvailabilityService.MIN_WINDOW_MINUTES) {
            throw ApiException.unprocessable("SCHEDULE_TOO_SHORT",
                    "Commute windows need to be at least 30 minutes long.");
        }
        Integer rateCents = req.hourlyRateCents();
        if (rateCents != null
                && (rateCents <= 0 || rateCents > AvailabilityService.MAX_HOURLY_RATE_CENTS)) {
            throw ApiException.unprocessable("INVALID_PRICE",
                    "The hourly price must be between $0.01 and $100.00 — or leave the commute free.");
        }
        String timezone = req.timezone() == null || req.timezone().isBlank()
                ? "UTC" : req.timezone().trim();
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.unprocessable("INVALID_TIMEZONE",
                    "That time zone isn't valid. Use an IANA name like America/Chicago.");
        }
        if (schedules.existsBySpaceIdAndDayOfWeek(spaceId, req.dayOfWeek())) {
            throw ApiException.conflict("SCHEDULE_EXISTS",
                    "You already have a commute entry for "
                            + DAY_NAMES[req.dayOfWeek()]
                            + ". Delete it first to change the times.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        CommuteSchedule schedule = new CommuteSchedule(space, req.dayOfWeek(), start, end,
                rateCents, timezone, now);
        CommuteSchedule saved = schedules.save(schedule);
        materializeForSpace(space, now);
        return CommuteScheduleDto.from(saved);
    }

    /** All weekly commute entries for one of the host's spaces, Monday first. */
    @Transactional(readOnly = true)
    public List<CommuteScheduleDto> listSchedules(UUID hostId, UUID spaceId) {
        ownedSpace(hostId, spaceId);
        return schedules.findBySpaceIdOrderByDayOfWeekAsc(spaceId).stream()
                .map(CommuteScheduleDto::from)
                .toList();
    }

    /**
     * Deletes a commute entry and its future unreserved COMMUTE windows
     * (windows with a confirmed reservation are kept — a driver is never
     * silently cancelled). Windows from the space's other active schedules
     * are re-materialized.
     */
    @Transactional
    public void deleteSchedule(UUID hostId, UUID scheduleId) {
        CommuteSchedule schedule = ownedSchedule(hostId, scheduleId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        schedules.delete(schedule);
        refreshSpaceWindows(schedule.getSpace(), now);
    }

    /**
     * Pauses a schedule: no new windows are materialized, and future
     * unreserved COMMUTE windows for the space are removed. Windows with a
     * confirmed reservation are kept; live windows expire on their own.
     */
    @Transactional
    public CommuteScheduleDto pause(UUID hostId, UUID scheduleId) {
        CommuteSchedule schedule = ownedSchedule(hostId, scheduleId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        schedule.setActive(false, now);
        schedules.save(schedule);
        refreshSpaceWindows(schedule.getSpace(), now);
        return CommuteScheduleDto.from(schedule);
    }

    /** Resumes a paused schedule and materializes its windows immediately. */
    @Transactional
    public CommuteScheduleDto resume(UUID hostId, UUID scheduleId) {
        CommuteSchedule schedule = ownedSchedule(hostId, scheduleId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        schedule.setActive(true, now);
        schedules.save(schedule);
        materializeForSpace(schedule.getSpace(), now);
        return CommuteScheduleDto.from(schedule);
    }

    /**
     * Materializes the active schedules of one space. Idempotent: a window is
     * skipped when the same (space, period, COMMUTE) window already exists,
     * and a manual share always wins — the materializer never creates an
     * overlapping window. Deactivated spaces are skipped.
     */
    @Transactional
    public void materializeSpace(UUID spaceId) {
        ParkingSpace space = spaces.findById(spaceId).orElse(null);
        if (space == null || !space.isActive()) {
            return;
        }
        materializeForSpace(space, OffsetDateTime.now(clock));
    }

    /**
     * Removes future unreserved COMMUTE windows for the space, then
     * re-materializes the space's remaining active schedules. Windows with a
     * confirmed reservation — and windows that already started — are kept.
     */
    private void refreshSpaceWindows(ParkingSpace space, OffsetDateTime now) {
        for (AvailabilityWindow window : windows
                .findBySpaceIdAndSourceAndStartsAtAfter(space.getId(), WindowSource.COMMUTE, now)) {
            if (reservations.existsConfirmedOverlap(space.getId(),
                    window.getStartsAt(), window.getEndsAt())) {
                continue;
            }
            windows.delete(window);
        }
        materializeForSpace(space, now);
    }

    private void materializeForSpace(ParkingSpace space, OffsetDateTime now) {
        List<CommuteSchedule> active =
                schedules.findBySpaceIdAndActiveTrueOrderByDayOfWeekAsc(space.getId());
        for (CommuteSchedule schedule : active) {
            materializeSchedule(space, schedule, now);
        }
    }

    private void materializeSchedule(ParkingSpace space, CommuteSchedule schedule,
                                     OffsetDateTime now) {
        ZoneId zone;
        try {
            zone = ZoneId.of(schedule.getTimezone());
        } catch (DateTimeException e) {
            // Validated at creation; a bad row must never crash the sweep.
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        for (int d = 0; d < CommuteMaterializer.DAYS_AHEAD; d++) {
            LocalDate date = today.plusDays(d);
            if (date.getDayOfWeek().getValue() - 1 != schedule.getDayOfWeek()) {
                continue;
            }
            OffsetDateTime startsAt = date.atTime(schedule.getStartTime())
                    .atZone(zone).toOffsetDateTime();
            OffsetDateTime endsAt = date.atTime(schedule.getEndTime())
                    .atZone(zone).toOffsetDateTime();
            if (!endsAt.isAfter(now)) {
                continue;
            }
            if (windows.existsBySpaceIdAndStartsAtAndEndsAtAndSource(
                    space.getId(), startsAt, endsAt, WindowSource.COMMUTE)) {
                continue;
            }
            if (!windows.findOverlapping(space.getId(), startsAt, endsAt).isEmpty()) {
                // A manual share already covers this period — it wins.
                continue;
            }
            // Backstop: the natural unique key on
            // (space_id, starts_at, ends_at, source) guarantees no duplicates
            // even under a true race.
            windows.save(new AvailabilityWindow(space, startsAt, endsAt,
                    WindowSource.COMMUTE, schedule.getHourlyRateCents(), now));
        }
    }

    private ParkingSpace ownedSpace(UUID hostId, UUID spaceId) {
        ParkingSpace space = spaces.findById(spaceId)
                .orElseThrow(() -> ApiException.notFound("SPACE_NOT_FOUND",
                        "We couldn't find that parking space."));
        if (!space.getHost().getId().equals(hostId)) {
            throw ApiException.forbidden("NOT_YOUR_SPACE",
                    "This parking space belongs to another account.");
        }
        return space;
    }

    private CommuteSchedule ownedSchedule(UUID hostId, UUID scheduleId) {
        CommuteSchedule schedule = schedules.findById(scheduleId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND",
                        "We couldn't find that commute schedule."));
        if (!schedule.getSpace().getHost().getId().equals(hostId)) {
            throw ApiException.forbidden("NOT_YOUR_SPACE",
                    "This parking space belongs to another account.");
        }
        return schedule;
    }
}
