package com.spotshare.availability;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spotshare.auth.AuthenticatedUser;
import com.spotshare.availability.dto.AvailabilityWindowDto;
import com.spotshare.availability.dto.ReturnEarlyRequest;
import com.spotshare.availability.dto.ShareRequest;
import com.spotshare.availability.dto.VacationRequest;

import jakarta.validation.Valid;

/**
 * Host availability endpoints. Every route requires the caller to be the
 * space's host (403 otherwise). Expiry is derived from the window timestamps,
 * so there is no "close window" endpoint — ended windows simply stop being
 * live.
 */
@RestController
@RequestMapping("/api/v1")
public class AvailabilityController {

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    /**
     * The one-tap "I'm leaving" share. The window starts now; the body only
     * carries the return time and the optional hourly price in cents
     * (null = free).
     */
    @PostMapping("/spaces/{id}/availability")
    public ResponseEntity<AvailabilityWindowDto> share(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID spaceId,
            @Valid @RequestBody ShareRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(availability.share(principal.id(), spaceId, req));
    }

    /** Upcoming and currently-live shares for one of the host's spaces. */
    @GetMapping("/spaces/{id}/availability")
    public List<AvailabilityWindowDto> listForSpace(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID spaceId) {
        return availability.listWindows(principal.id(), spaceId);
    }

    /**
     * Vacation mode: the host shares their spot for a whole trip — a
     * multi-day window (e.g. Friday 18:00 → Monday 09:00). The host picks
     * both the start and the end; the optional hourly price is in cents
     * (null = free). Ending the vacation early is the existing return-early
     * endpoint — it shrinks the window and never silently cancels a driver.
     */
    @PostMapping("/spaces/{id}/vacation")
    public ResponseEntity<AvailabilityWindowDto> vacation(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID spaceId,
            @Valid @RequestBody VacationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(availability.vacation(principal.id(), spaceId, req));
    }

    /** Upcoming and currently-live shares across all of the host's spaces. */
    @GetMapping("/availability/mine")
    public List<AvailabilityWindowDto> mine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return availability.myWindows(principal.id());
    }

    /**
     * Return early: the host moves their return time sooner and the window
     * shrinks. Confirmed reservations are never silently cancelled — if a
     * driver is parked past the requested return, the error carries
     * {@code earliestReturnTime} in its details.
     */
    @PostMapping("/availability/{windowId}/return-early")
    public AvailabilityWindowDto returnEarly(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("windowId") UUID windowId,
            @Valid @RequestBody ReturnEarlyRequest req) {
        return availability.returnEarly(principal.id(), windowId, req.newReturnTime());
    }

    /**
     * Removes a share that hasn't started yet. Idempotent — removing a share
     * that doesn't exist is a no-op success (204), so double-tap retries are
     * safe.
     */
    @DeleteMapping("/availability/{windowId}")
    public ResponseEntity<Void> remove(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("windowId") UUID windowId) {
        availability.removeWindow(principal.id(), windowId);
        return ResponseEntity.noContent().build();
    }
}
