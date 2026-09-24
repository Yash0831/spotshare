package com.spotshare.reservation;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The completion scheduler without a database: it reads time from the
 * shared {@link Clock} and asks the repository to complete exactly the
 * CONFIRMED reservations whose departure has passed — nothing more.
 */
@ExtendWith(MockitoExtension.class)
class ReservationCompletionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private ReservationRepository reservations;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void completesOnlyConfirmedReservationsPastDeparture() {
        var scheduler = new ReservationCompletionScheduler(reservations, clock);

        scheduler.completeExpiredReservations();

        // The bulk update carries the clock's "now" and both statuses, so
        // cancelled rows are never touched and completed rows aren't redone.
        verify(reservations).markCompleted(
                OffsetDateTime.now(clock),
                ReservationStatus.CONFIRMED,
                ReservationStatus.COMPLETED);
    }
}
