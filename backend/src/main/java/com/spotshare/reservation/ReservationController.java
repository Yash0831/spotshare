package com.spotshare.reservation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.spotshare.auth.AuthenticatedUser;
import com.spotshare.reservation.dto.CreateReservationRequest;
import com.spotshare.reservation.dto.HostArrivalDto;
import com.spotshare.reservation.dto.ReservationDetailDto;
import com.spotshare.reservation.dto.ReservationDto;

import jakarta.validation.Valid;

/**
 * Reservation endpoints. Driver routes let a driver see only their own
 * reservations; the host route below lets a host see only their own
 * spaces' reservations. The exact address is revealed only by the
 * authorized detail view (spec §11).
 */
@RestController
@RequestMapping("/api/v1")
public class ReservationController {

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /**
     * Books a space for {@code [arrival, departure)}. The optional
     * {@code Idempotency-Key} header makes double-taps safe: the same key
     * from the same user returns the original reservation (200) instead of
     * a duplicate. A fresh booking returns 201.
     */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationDto> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateReservationRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ReservationService.CreateResult result =
                reservations.create(principal.id(), req, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.reservation());
    }

    /**
     * The driver's reservations. {@code filter} is {@code upcoming},
     * {@code active}, or {@code past}; omitted returns upcoming + past.
     */
    @GetMapping("/reservations/mine")
    public List<ReservationDto> mine(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(value = "filter", required = false) String filter) {
        return reservations.mine(principal.id(), filter);
    }

    /**
     * The authorized detail — the only response carrying the exact address,
     * space number, and parking/access instructions (driver while CONFIRMED,
     * or the space's host).
     */
    @GetMapping("/reservations/{id}")
    public ReservationDetailDto get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID reservationId) {
        return reservations.get(principal.id(), reservationId);
    }

    /**
     * Driver or host cancellation. The driver cancels their own booking;
     * the host cancels a booking on one of their spaces — recorded as
     * host-cancelled, never silent. Releases the period; idempotent —
     * cancelling an already-cancelled reservation returns its current
     * state.
     */
    @PostMapping("/reservations/{id}/cancel")
    public ReservationDto cancel(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID reservationId) {
        return reservations.cancel(principal.id(), reservationId);
    }

    /**
     * The host's arrivals view for one of their spaces (spec §12): that
     * day's reservations — driver as first name + last initial, arrival,
     * departure, code, status. {@code date} is {@code YYYY-MM-DD} in UTC;
     * omitted means today.
     */
    @GetMapping("/spaces/{id}/reservations")
    public List<HostArrivalDto> hostArrivals(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable("id") UUID spaceId,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reservations.arrivalsForSpace(principal.id(), spaceId, date);
    }
}
