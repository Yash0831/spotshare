package com.spotshare.reservation;

/**
 * Reservation lifecycle states. Cancellation is terminal; after arrival passes,
 * only the host can mark a reservation completed (a scheduler also
 * auto-completes past CONFIRMED reservations).
 */
public enum ReservationStatus {
    CONFIRMED,
    COMPLETED,
    CANCELLED_BY_DRIVER,
    CANCELLED_BY_HOST
}
