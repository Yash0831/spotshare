package com.spotshare.availability.commute.dto;

import java.time.LocalTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Create one weekly commute entry. Times are local times-of-day in
 * {@code timezone}; the frontend sends the host's device timezone
 * (e.g. "America/Chicago") so the windows land at the right local hour.
 */
public record CreateCommuteScheduleRequest(
        /** 0 = Monday .. 6 = Sunday. */
        @Min(0) @Max(6) int dayOfWeek,

        @NotNull LocalTime startTime,

        @NotNull LocalTime endTime,

        /** Integer cents per hour, or null for a free commute window. */
        Integer hourlyRateCents,

        /** IANA zone name; defaults to UTC when omitted. */
        String timezone
) {
}
