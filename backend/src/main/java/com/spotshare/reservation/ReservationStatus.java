package com.spotshare.reservation;

/**
 * Reservation lifecycle. The spec's "cancelled by driver / cancelled by
 * host" distinction is recorded in {@link Reservation#getCancelledBy()};
 * the status itself stays a simple three-state value.
 */
public enum ReservationStatus {
    CONFIRMED,
    CANCELLED,
    COMPLETED
}
