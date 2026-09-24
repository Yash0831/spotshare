package com.spotshare.availability;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spotshare.availability.dto.AvailabilityWindowDto;
import com.spotshare.availability.dto.ShareRequest;
import com.spotshare.availability.dto.VacationRequest;
import com.spotshare.common.ApiException;
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;
import com.spotshare.reservation.ReservationRepository;
import com.spotshare.reservation.ReservationStatus;

/**
 * Temporary availability — the signature "I'm leaving / Share My Spot" flow.
 *
 * <p>A share window covers the half-open period {@code [now, returnTime)}.
 * Expiry is <em>derived</em> from the timestamps: a window is live while
 * {@code startsAt <= now < endsAt}, so ended windows simply stop being live —
 * there is no status column to update and no expiry scheduler to run. The
 * {@link Clock} bean is the only source of "now", so expiry is unit-testable
 * with a fixed clock.
 *
 * <p>Every space-scoped operation verifies the caller is the space's host;
 * a different authenticated user gets a 403, never the data.
 */
@Service
public class AvailabilityService {

    /** A share must be at least 30 minutes — shorter shares aren't useful. */
    public static final int MIN_WINDOW_MINUTES = 30;

    /** Sanity cap: $100/hour. Integer cents, never floating point. */
    public static final int MAX_HOURLY_RATE_CENTS = 10_000;

    /** "7:30 PM" — the host-readable time in the timestamp's own offset. */
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("h:mm a");

    /** Grace for clock skew when the host taps "I'm back now". */
    private static final Duration PAST_GRACE = Duration.ofSeconds(60);

    private final AvailabilityWindowRepository windows;
    private final ParkingSpaceRepository spaces;
    private final ReservationRepository reservations;
    private final Clock clock;

    public AvailabilityService(AvailabilityWindowRepository windows,
                               ParkingSpaceRepository spaces,
                               ReservationRepository reservations,
                               Clock clock) {
        this.windows = windows;
        this.spaces = spaces;
        this.reservations = reservations;
        this.clock = clock;
    }

    /**
     * The one-tap share: the window starts now and ends at the host's return
     * time. Overlapping windows for the same space are rejected — a
     * reservation must be contained in a single unambiguous window (Phase 5).
     */
    @Transactional
    public AvailabilityWindowDto share(UUID hostId, UUID spaceId, ShareRequest req) {
        ParkingSpace space = ownedSpace(hostId, spaceId);
        if (!space.isActive()) {
            throw new ApiException(HttpStatus.GONE, "SPACE_INACTIVE",
                    "This parking space is deactivated. Reactivate it before sharing.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime returnTime = req.returnTime();
        if (!returnTime.isAfter(now)) {
            throw ApiException.unprocessable("INVALID_RETURN_TIME",
                    "Your return time must be in the future.");
        }
        if (Duration.between(now, returnTime).toMinutes() < MIN_WINDOW_MINUTES) {
            throw ApiException.unprocessable("WINDOW_TOO_SHORT",
                    "Shares need to be at least 30 minutes long.");
        }
        Integer rateCents = req.hourlyRateCents();
        if (rateCents != null && (rateCents <= 0 || rateCents > MAX_HOURLY_RATE_CENTS)) {
            throw ApiException.unprocessable("INVALID_PRICE",
                    "The hourly price must be between $0.01 and $100.00 — or leave the share free.");
        }
        if (!windows.findOverlapping(spaceId, now, returnTime).isEmpty()) {
            throw ApiException.conflict("OVERLAPPING_WINDOW",
                    "This space is already shared for part of that time. "
                    + "Remove the existing share first, or pick a later return time.");
        }
        AvailabilityWindow window = new AvailabilityWindow(space, now, returnTime,
                WindowSource.MANUAL, rateCents, now);
        return toDto(windows.save(window), now);
    }

    /**
     * Vacation mode (spec §7/§16): the host shares their spot for a whole
     * trip — a window spanning one or many days, e.g. Friday 18:00 to
     * Monday 09:00. One row in {@code availability_windows} with
     * {@code source=VACATION}; booking, search, return-early, and the
     * reservation-protection rules apply unchanged.
     *
     * <p>Validation, in order:
     * <ol>
     *   <li>the space exists (404) and belongs to the host (403), and is
     *       active (410);</li>
     *   <li>the start is not in the past (a 60-second grace covers clock
     *       skew when the host starts their vacation now);</li>
     *   <li>the end is strictly after the start;</li>
     *   <li>the trip is at least 30 minutes long (the same minimum as a
     *       one-tap share); there is no maximum — a two-week vacation is
     *       legitimate;</li>
     *   <li>the rate is free or 1..10000 cents (the same cap as shares);</li>
     *   <li>no existing window overlaps {@code [start, end)} — adjacent
     *       windows are allowed, exactly like manual shares.</li>
     * </ol>
     *
     * <p>Ending the vacation early is the existing return-early path
     * (Phase 7): {@link #returnEarly} shrinks the window and never silently
     * cancels a driver.
     */
    @Transactional
    public AvailabilityWindowDto vacation(UUID hostId, UUID spaceId, VacationRequest req) {
        ParkingSpace space = ownedSpace(hostId, spaceId);
        if (!space.isActive()) {
            throw new ApiException(HttpStatus.GONE, "SPACE_INACTIVE",
                    "This parking space is deactivated. Reactivate it before sharing.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime start = req.startDateTime();
        if (start.isBefore(now.minus(PAST_GRACE))) {
            throw ApiException.unprocessable("INVALID_START_TIME",
                    "Your vacation start must be in the future.");
        }
        OffsetDateTime end = req.endDateTime();
        if (!end.isAfter(start)) {
            throw ApiException.unprocessable("INVALID_END_TIME",
                    "Your vacation end must be after your start.");
        }
        if (Duration.between(start, end).toMinutes() < MIN_WINDOW_MINUTES) {
            throw ApiException.unprocessable("WINDOW_TOO_SHORT",
                    "Shares need to be at least 30 minutes long.");
        }
        Integer rateCents = req.hourlyRateCents();
        if (rateCents != null && (rateCents <= 0 || rateCents > MAX_HOURLY_RATE_CENTS)) {
            throw ApiException.unprocessable("INVALID_PRICE",
                    "The hourly price must be between $0.01 and $100.00 — or leave the share free.");
        }
        if (!windows.findOverlapping(spaceId, start, end).isEmpty()) {
            throw ApiException.conflict("OVERLAPPING_WINDOW",
                    "This space is already shared for part of that trip. "
                    + "Remove the existing share first, or pick different dates.");
        }
        AvailabilityWindow window = new AvailabilityWindow(space, start, end,
                WindowSource.VACATION, rateCents, now);
        return toDto(windows.save(window), now);
    }

    /** Upcoming and currently-live windows for one of the host's spaces. */
    @Transactional(readOnly = true)
    public List<AvailabilityWindowDto> listWindows(UUID hostId, UUID spaceId) {
        ownedSpace(hostId, spaceId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return windows.findBySpaceIdAndEndsAtAfterOrderByStartsAtAsc(spaceId, now).stream()
                .map(w -> toDto(w, now))
                .toList();
    }

    /** Upcoming and currently-live windows across all of the host's spaces. */
    @Transactional(readOnly = true)
    public List<AvailabilityWindowDto> myWindows(UUID hostId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return windows.findBySpace_Host_IdAndEndsAtAfterOrderByStartsAtAsc(hostId, now).stream()
                .map(w -> toDto(w, now))
                .toList();
    }

    /**
     * Removes a share that hasn't started yet. Idempotent: removing a window
     * that doesn't exist (e.g. a double-tap retry) is a no-op success, so the
     * client never has to guess.
     */
    @Transactional
    public void removeWindow(UUID hostId, UUID windowId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AvailabilityWindow window = windows.findById(windowId).orElse(null);
        if (window == null) {
            return;
        }
        if (!window.getSpace().getHost().getId().equals(hostId)) {
            throw ApiException.forbidden("NOT_YOUR_WINDOW",
                    "This share belongs to another account.");
        }
        if (window.hasStarted(now)) {
            throw ApiException.unprocessable("WINDOW_ALREADY_STARTED",
                    "This share has already started, so it can't be removed. "
                    + "It ends automatically at your return time.");
        }
        windows.delete(window);
    }

    /**
     * Return early (spec §7): the host moves their return time sooner. The
     * window simply shrinks — confirmed reservations keep their exact
     * periods and are never silently cancelled.
     *
     * <p>Validation, in order:
     * <ol>
     *   <li>the window exists (404) and belongs to the host (403);</li>
     *   <li>an already-ended window is a no-op success (idempotent);</li>
     *   <li>the new return is not in the past (a 60-second grace covers
     *       clock skew when the host taps "I'm back now");</li>
     *   <li>the new return is strictly earlier than the current end;</li>
     *   <li>the window keeps its 30-minute minimum length;</li>
     *   <li>the new return is not earlier than the latest CONFIRMED
     *       reservation's departure — a parked driver is never cut off.
     *       Rejection carries {@code earliestReturnTime} in the error
     *       details so the UI can show exactly when the host may return.
     *       Ending exactly when a reservation ends is allowed: the window
     *       is half-open {@code [startsAt, endsAt)}.</li>
     * </ol>
     */
    @Transactional
    public AvailabilityWindowDto returnEarly(UUID hostId, UUID windowId,
                                             OffsetDateTime newReturn) {
        AvailabilityWindow window = windows.findById(windowId)
                .orElseThrow(() -> ApiException.notFound("WINDOW_NOT_FOUND",
                        "We couldn't find that share."));
        if (!window.getSpace().getHost().getId().equals(hostId)) {
            throw ApiException.forbidden("NOT_YOUR_WINDOW",
                    "This share belongs to another account.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!now.isBefore(window.getEndsAt())) {
            // Already over — nothing to do; idempotent no-op success.
            return toDto(window, now);
        }
        if (newReturn == null || newReturn.isBefore(now.minus(PAST_GRACE))) {
            throw ApiException.unprocessable("INVALID_RETURN_TIME",
                    "Your new return time must be in the future.");
        }
        if (!newReturn.isBefore(window.getEndsAt())) {
            throw ApiException.unprocessable("NOT_EARLY_RETURN",
                    "That's not earlier than your current return time ("
                    + SHORT_TIME.format(window.getEndsAt()) + ").");
        }
        if (Duration.between(window.getStartsAt(), newReturn).toMinutes()
                < MIN_WINDOW_MINUTES) {
            throw ApiException.unprocessable("WINDOW_TOO_SHORT",
                    "Shares need to be at least 30 minutes long.");
        }
        Optional<OffsetDateTime> latestDeparture =
                reservations.findLatestConfirmedDepartureInWindow(
                        window.getSpace().getId(), ReservationStatus.CONFIRMED,
                        window.getStartsAt(), window.getEndsAt());
        if (latestDeparture.isPresent()
                && newReturn.isBefore(latestDeparture.get())) {
            OffsetDateTime earliest = latestDeparture.get();
            String when = SHORT_TIME.format(earliest);
            throw ApiException.unprocessable("RETURN_BLOCKED_BY_RESERVATION",
                    "Your space is reserved until " + when
                    + ". Earliest available return: " + when + ".",
                    Map.of("earliestReturnTime", earliest.toString()));
        }
        window.setEndsAt(newReturn);
        return toDto(windows.save(window), now);
    }

    /** 404 when the space doesn't exist; 403 when it belongs to someone else. */
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

    private AvailabilityWindowDto toDto(AvailabilityWindow window, OffsetDateTime now) {
        return new AvailabilityWindowDto(
                window.getId(),
                window.getSpace().getId(),
                window.getStartsAt(),
                window.getEndsAt(),
                window.getSource(),
                window.getHourlyRateCents(),
                window.isLive(now),
                window.getCreatedAt());
    }
}
