package com.spotshare.reservation;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservation completion (spec §7): a CONFIRMED reservation whose departure
 * time has passed becomes COMPLETED. This is a plain scheduled bulk update —
 * no events, no messaging, nothing fancier.
 *
 * <p>The update filters on CONFIRMED, so a cancelled reservation is never
 * overwritten: once a driver or host cancels, the record stays cancelled.
 * Time comes from the shared {@link Clock} bean, so the rule is
 * unit-testable with a fixed clock. Runs every 60 seconds.
 */
@Service
public class ReservationCompletionScheduler {

    private final ReservationRepository reservations;
    private final Clock clock;

    public ReservationCompletionScheduler(ReservationRepository reservations, Clock clock) {
        this.reservations = reservations;
        this.clock = clock;
    }

    /** Every minute: CONFIRMED reservations with {@code departure <= now} become COMPLETED. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void completeExpiredReservations() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        reservations.markCompleted(now, ReservationStatus.CONFIRMED, ReservationStatus.COMPLETED);
    }
}
