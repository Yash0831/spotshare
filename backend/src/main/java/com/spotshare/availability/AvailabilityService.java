package com.spotshare.availability;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spotshare.availability.dto.AvailabilityWindowDto;
import com.spotshare.availability.dto.ShareRequest;
import com.spotshare.common.ApiException;
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;

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

    private final AvailabilityWindowRepository windows;
    private final ParkingSpaceRepository spaces;
    private final Clock clock;

    public AvailabilityService(AvailabilityWindowRepository windows,
                               ParkingSpaceRepository spaces,
                               Clock clock) {
        this.windows = windows;
        this.spaces = spaces;
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
