package com.spotshare.reservation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link Reservation}. */
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * Application-level double-booking check: any CONFIRMED reservation for
     * the space whose half-open period overlaps {@code [arrival, departure)}.
     * Adjacent periods (one ends exactly when the other starts) do not
     * overlap. The exclusion constraint is the final backstop.
     */
    @Query(value = """
            SELECT COUNT(*) > 0 FROM reservations
            WHERE space_id = :spaceId
              AND status = 'CONFIRMED'
              AND period && tstzrange(
                    CAST(:arrival AS timestamptz),
                    CAST(:departure AS timestamptz), '[)')
            """, nativeQuery = true)
    boolean existsConfirmedOverlap(@Param("spaceId") UUID spaceId,
                                   @Param("arrival") OffsetDateTime arrival,
                                   @Param("departure") OffsetDateTime departure);

    /** Idempotency replay: the driver's earlier reservation for this key, if any. */
    Optional<Reservation> findByDriverIdAndIdempotencyKey(UUID driverId, String idempotencyKey);

    /** Upcoming: confirmed and not yet over, soonest arrival first. */
    @Query("select r from Reservation r join fetch r.space"
            + " where r.driver.id = :driverId and r.status = :status"
            + " and r.departure > :now order by r.arrival asc")
    List<Reservation> findUpcoming(@Param("driverId") UUID driverId,
                                   @Param("status") ReservationStatus status,
                                   @Param("now") OffsetDateTime now);

    /** Active right now: confirmed and {@code arrival <= now < departure}. */
    @Query("select r from Reservation r join fetch r.space"
            + " where r.driver.id = :driverId and r.status = :status"
            + " and r.arrival <= :now and r.departure > :now order by r.departure asc")
    List<Reservation> findActive(@Param("driverId") UUID driverId,
                                 @Param("status") ReservationStatus status,
                                 @Param("now") OffsetDateTime now);

    /** Past: finished, cancelled, or completed — most recent departure first. */
    @Query("select r from Reservation r join fetch r.space"
            + " where r.driver.id = :driverId"
            + " and (r.status <> :confirmed or r.departure <= :now)"
            + " order by r.departure desc")
    List<Reservation> findPast(@Param("driverId") UUID driverId,
                               @Param("confirmed") ReservationStatus confirmed,
                               @Param("now") OffsetDateTime now);
}
