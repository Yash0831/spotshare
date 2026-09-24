package com.spotshare.availability.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

/**
 * The one-tap "I'm leaving" share. The window always starts now; the host
 * only picks the return time and the price.
 *
 * <p>{@code hourlyRateCents} is the price in integer cents, or {@code null}
 * for a free share. Range validation lives in the service (1..10000 cents)
 * so the friendly error messages stay next to the domain rule.
 */
public record ShareRequest(
        /** The host's return time. Must be in the future (checked in the service). */
        @NotNull(message = "Choose a return time.")
        OffsetDateTime returnTime,

        /** Null = free. Otherwise integer cents per hour. */
        Integer hourlyRateCents
) {
}
