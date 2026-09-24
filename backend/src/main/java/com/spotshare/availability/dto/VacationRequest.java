package com.spotshare.availability.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

/**
 * Vacation mode: the host shares their spot for a whole trip — a window
 * spanning multiple days. The host picks both the start and the end
 * (unlike the one-tap share, which always starts now).
 *
 * <p>{@code hourlyRateCents} is the price in integer cents, or {@code null}
 * for a free share. Range and overlap validation live in the service so the
 * friendly error messages stay next to the domain rule.
 */
public record VacationRequest(
        /** When the host leaves. Must not be in the past (checked in the service). */
        @NotNull(message = "Choose when your vacation starts.")
        OffsetDateTime startDateTime,

        /** When the host returns. Must be after the start (checked in the service). */
        @NotNull(message = "Choose when your vacation ends.")
        OffsetDateTime endDateTime,

        /** Null = free. Otherwise integer cents per hour. */
        Integer hourlyRateCents
) {
}
