package com.spotshare.reservation;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.spotshare.availability.AvailabilityWindow;
import com.spotshare.availability.AvailabilityWindowRepository;
import com.spotshare.common.ApiException;
import com.spotshare.common.Money;
import com.spotshare.parking.ParkingSpace;
import com.spotshare.parking.ParkingSpaceRepository;
import com.spotshare.reservation.dto.CreateReservationRequest;
import com.spotshare.reservation.dto.ReservationDetailDto;
import com.spotshare.reservation.dto.ReservationDto;
import com.spotshare.user.User;
import com.spotshare.user.UserRepository;

/**
 * Driver booking. Implements the spec's §9 concurrency strategy in three
 * layers, cheapest first:
 *
 * <ol>
 *   <li><b>Application checks</b> inside one transaction: the space is
 *   active, the driver isn't the host, the requested half-open period
 *   {@code [arrival, departure)} lies entirely inside a single availability
 *   window, and no CONFIRMED reservation already overlaps it.</li>
 *   <li><b>Per-space advisory lock</b> —
 *   {@code pg_advisory_xact_lock(hashtext(space_id::text))} — so concurrent
 *   bookings for the same space serialize; different spaces never block
 *   each other.</li>
 *   <li><b>Database backstop</b>: the {@code excl_reservations_no_overlap}
 *   exclusion constraint (Flyway V4) rejects any overlap the app layer
 *   missed. Its violation is translated to the friendly 409, never raw SQL.
 * </ol>
 *
 * <p>Because PostgreSQL aborts a transaction on the first failed statement,
 * the insert attempt runs inside a {@link TransactionTemplate} with a small
 * retry loop: a reservation-code collision or an idempotency race is
 * recovered in a fresh transaction instead of surfacing as an error.
 */
@Service
public class ReservationService {

    /** 60s grace so a phone clock slightly ahead of the server isn't rejected. */
    private static final long PAST_GRACE_SECONDS = 60;

    /** Reservation codes: 'SP-' + 5 chars from an unambiguous alphabet (no 0/O, 1/I). */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_RANDOM_CHARS = 5;

    /** Code collisions are retried with a fresh code; 3 attempts is plenty. */
    private static final int MAX_CREATE_ATTEMPTS = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReservationRepository reservations;
    private final ParkingSpaceRepository spaces;
    private final AvailabilityWindowRepository windows;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReservationService(ReservationRepository reservations,
                              ParkingSpaceRepository spaces,
                              AvailabilityWindowRepository windows,
                              UserRepository users,
                              JdbcTemplate jdbc,
                              TransactionTemplate tx,
                              Clock clock) {
        this.reservations = reservations;
        this.spaces = spaces;
        this.windows = windows;
        this.users = users;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** Result of a booking: the reservation, plus whether it was just created (201) or replayed (200). */
    public record CreateResult(ReservationDto reservation, boolean created) {
    }

    /**
     * Books {@code [arrival, departure)} for the driver. Idempotent per
     * (driver, key): a repeat POST with the same {@code Idempotency-Key}
     * returns the original reservation with {@code created=false}.
     */
    public CreateResult create(UUID driverId, CreateReservationRequest req, String idempotencyKey) {
        OffsetDateTime arrival = req.arrival();
        OffsetDateTime departure = req.departure();
        if (!arrival.isBefore(departure)) {
            throw ApiException.unprocessable("INVALID_PERIOD",
                    "Your arrival time must be before your departure time.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (arrival.isBefore(now.minusSeconds(PAST_GRACE_SECONDS))) {
            throw ApiException.unprocessable("ARRIVAL_IN_PAST",
                    "Your arrival time is in the past. Please pick a future time.");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? null : idempotencyKey.strip();
        if (key != null && key.length() > 64) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "The idempotency key is too long.");
        }

        for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> insertAttempt(driverId, req, key, now));
            } catch (IdempotencyRace race) {
                // A concurrent request with the same key won: fetch it in a
                // fresh transaction and return it — never a duplicate.
                Reservation existing = tx.execute(s ->
                        reservations.findByDriverIdAndIdempotencyKey(driverId, key)
                                .orElse(null));
                if (existing != null) {
                    return new CreateResult(ReservationDto.from(existing), false);
                }
                // Not the key after all (a vanishingly rare code collision on
                // a keyed request) — fall through and retry with a fresh code.
            } catch (CodeCollision collision) {
                // Retry with a freshly generated code.
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "BOOKING_FAILED",
                "We couldn't complete your reservation. Please try again.");
    }

    /** One booking attempt: all checks, the advisory lock, and the insert. */
    private CreateResult insertAttempt(UUID driverId, CreateReservationRequest req, String key,
                                       OffsetDateTime now) {
        OffsetDateTime arrival = req.arrival();
        OffsetDateTime departure = req.departure();

        ParkingSpace space = spaces.findById(req.spaceId())
                .orElseThrow(() -> ApiException.notFound("SPACE_NOT_FOUND",
                        "We couldn't find that parking space."));
        if (!space.isActive()) {
            throw new ApiException(HttpStatus.GONE, "SPACE_INACTIVE",
                    "This parking space is no longer available.");
        }
        if (space.getHost().getId().equals(driverId)) {
            throw ApiException.forbidden("CANNOT_BOOK_OWN_SPACE",
                    "You can't reserve your own parking space.");
        }

        takeAdvisoryLock(space.getId());

        if (key != null) {
            var replayed = reservations.findByDriverIdAndIdempotencyKey(driverId, key);
            if (replayed.isPresent()) {
                return new CreateResult(ReservationDto.from(replayed.get()), false);
            }
        }

        AvailabilityWindow window = windows
                .findContaining(space.getId(), arrival, departure).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("PERIOD_NOT_AVAILABLE",
                        "This parking space is no longer available for the selected time."));

        if (reservations.existsConfirmedOverlap(space.getId(), arrival, departure)) {
            throw ApiException.conflict("SPACE_JUST_RESERVED",
                    "This space was just reserved. Please choose another nearby space.");
        }

        int totalCents = Money.proratedTotalCents(
                window.getHourlyRateCents(), arrival.toInstant(), departure.toInstant());
        User driver = users.getReferenceById(driverId);
        Reservation reservation = new Reservation(space, driver, arrival, departure,
                window.getHourlyRateCents(), totalCents, generateCode(), key, now);
        try {
            reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            String sqlState = sqlStateOf(e);
            if ("23P01".equals(sqlState)) {
                // The exclusion backstop fired: someone booked it first.
                throw ApiException.conflict("SPACE_JUST_RESERVED",
                        "This space was just reserved. Please choose another nearby space.");
            }
            if ("23505".equals(sqlState)) {
                if (key != null) {
                    throw new IdempotencyRace();
                }
                throw new CodeCollision();
            }
            throw e;
        }
        return new CreateResult(ReservationDto.from(reservation), true);
    }

    /**
     * The driver's reservations. {@code filter} is {@code upcoming},
     * {@code active}, {@code past}, or omitted (upcoming + past, in that
     * order — what the My Reservations screen shows).
     */
    @Transactional(readOnly = true)
    public List<ReservationDto> mine(UUID driverId, String filter) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String f = filter == null ? "all" : filter.toLowerCase();
        List<Reservation> result = switch (f) {
            case "upcoming" -> reservations.findUpcoming(driverId, ReservationStatus.CONFIRMED, now);
            case "active" -> reservations.findActive(driverId, ReservationStatus.CONFIRMED, now);
            case "past" -> reservations.findPast(driverId, ReservationStatus.CONFIRMED, now);
            case "all" -> {
                List<Reservation> all = new ArrayList<>(
                        reservations.findUpcoming(driverId, ReservationStatus.CONFIRMED, now));
                all.addAll(reservations.findPast(driverId, ReservationStatus.CONFIRMED, now));
                yield all;
            }
            default -> throw ApiException.badRequest("INVALID_FILTER",
                    "Unknown filter. Use upcoming, active, or past.");
        };
        return result.stream().map(ReservationDto::from).toList();
    }

    /**
     * The authorized detail — the only response that reveals the exact
     * address, space number, and parking/access instructions. The space's
     * host always sees it; the driver sees it only while the reservation is
     * CONFIRMED (spec §11). Anyone else gets a 403, never the data.
     */
    @Transactional(readOnly = true)
    public ReservationDetailDto get(UUID callerId, UUID reservationId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND",
                        "We couldn't find that reservation."));
        boolean isHost = reservation.getSpace().getHost().getId().equals(callerId);
        boolean isDriver = reservation.getDriver().getId().equals(callerId);
        if (isHost) {
            return ReservationDetailDto.from(reservation);
        }
        if (isDriver && reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return ReservationDetailDto.from(reservation);
        }
        if (isDriver) {
            throw ApiException.forbidden("RESERVATION_NOT_ACTIVE",
                    "This reservation is no longer active, so the exact address is no longer shared.");
        }
        throw ApiException.forbidden("NOT_YOUR_RESERVATION",
                "This reservation belongs to another account.");
    }

    /**
     * Driver cancellation. Releases the period so the space becomes bookable
     * again. Idempotent: cancelling an already-cancelled reservation returns
     * its current state — a no-op success, never an error.
     */
    @Transactional
    public ReservationDto cancel(UUID driverId, UUID reservationId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND",
                        "We couldn't find that reservation."));
        if (!reservation.getDriver().getId().equals(driverId)) {
            throw ApiException.forbidden("NOT_YOUR_RESERVATION",
                    "This reservation belongs to another account.");
        }
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            return ReservationDto.from(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.COMPLETED) {
            throw ApiException.unprocessable("RESERVATION_COMPLETED",
                    "This reservation is already complete and can't be cancelled.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!now.isBefore(reservation.getArrival())) {
            throw ApiException.unprocessable("RESERVATION_STARTED",
                    "This reservation has already started and can't be cancelled.");
        }
        reservation.cancel(now, CancelledBy.DRIVER);
        return ReservationDto.from(reservations.save(reservation));
    }

    /**
     * Per-space transaction advisory lock (spec §9): held until this
     * transaction commits or rolls back, so concurrent bookings for the same
     * space serialize. Different spaces never block each other.
     */
    private void takeAdvisoryLock(UUID spaceId) {
        jdbc.execute((Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext(?))")) {
                ps.setString(1, spaceId.toString());
                ps.execute();
                return null;
            }
        });
    }

    /** 'SP-' + 5 chars from the unambiguous alphabet, e.g. "SP-K84D2". */
    static String generateCode() {
        StringBuilder code = new StringBuilder("SP-");
        for (int i = 0; i < CODE_RANDOM_CHARS; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** Walks the cause chain for the PostgreSQL SQLState (23P01, 23505, …). */
    static String sqlStateOf(DataIntegrityViolationException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }

    /** A concurrent request with the same idempotency key won the race. */
    private static class IdempotencyRace extends RuntimeException {
    }

    /** The generated reservation code collided — retry with a fresh one. */
    private static class CodeCollision extends RuntimeException {
    }
}
