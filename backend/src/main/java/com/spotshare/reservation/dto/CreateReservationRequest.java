package com.spotshare.reservation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Booking request. The idempotency key travels in the Idempotency-Key header, not the body. */
public record CreateReservationRequest(
        @NotNull(message = "Choose a parking space to reserve.") UUID spaceId,
        @NotNull(message = "Choose an arrival time.") OffsetDateTime arrival,
        @NotNull(message = "Choose a departure time.") OffsetDateTime departure) {
}
