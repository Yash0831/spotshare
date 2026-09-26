package com.spotshare.reservation.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

/** Extend-stay request: the new departure time. Must be after the current one. */
public record ExtendReservationRequest(
        @NotNull(message = "Choose a new departure time.") OffsetDateTime newDeparture) {
}
