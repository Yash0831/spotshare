package com.spotshare.availability.commute;

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
import com.spotshare.availability.commute.dto.CommuteScheduleDto;
import com.spotshare.availability.commute.dto.CreateCommuteScheduleRequest;

import jakarta.validation.Valid;

/**
 * Commute mode endpoints (Phase 8): weekly recurring availability. Every
 * route requires the caller to be the space's host (403 otherwise).
 */
@RestController
@RequestMapping("/api/v1")
public class CommuteController {

    private final CommuteService commutes;

    public CommuteController(CommuteService commutes) {
        this.commutes = commutes;
    }

    /**
     * Adds one weekly commute entry (one weekday + time range) and
     * materializes its windows immediately.
     */
    @PostMapping("/spaces/{id}/commute-schedules")
    public ResponseEntity<CommuteScheduleDto> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID spaceId,
            @Valid @RequestBody CreateCommuteScheduleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commutes.createSchedule(principal.id(), spaceId, req));
    }

    /** The weekly commute entries for one of the host's spaces, Monday first. */
    @GetMapping("/spaces/{id}/commute-schedules")
    public List<CommuteScheduleDto> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID spaceId) {
        return commutes.listSchedules(principal.id(), spaceId);
    }

    /**
     * Deletes a commute entry and its future unreserved COMMUTE windows
     * (windows with a confirmed reservation are kept). Idempotent-ish: a
     * missing schedule is 404, a double-delete simply 404s the second time.
     */
    @DeleteMapping("/commute-schedules/{scheduleId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("scheduleId") UUID scheduleId) {
        commutes.deleteSchedule(principal.id(), scheduleId);
        return ResponseEntity.noContent().build();
    }

    /** Pauses a schedule: stops future materialization and removes future unreserved COMMUTE windows. */
    @PostMapping("/commute-schedules/{scheduleId}/pause")
    public CommuteScheduleDto pause(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("scheduleId") UUID scheduleId) {
        return commutes.pause(principal.id(), scheduleId);
    }

    /** Resumes a paused schedule and materializes its windows immediately. */
    @PostMapping("/commute-schedules/{scheduleId}/resume")
    public CommuteScheduleDto resume(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("scheduleId") UUID scheduleId) {
        return commutes.resume(principal.id(), scheduleId);
    }
}
