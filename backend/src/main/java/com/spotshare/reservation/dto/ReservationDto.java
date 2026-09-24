package com.spotshare.reservation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.spotshare.parking.ParkingType;
import com.spotshare.reservation.CancelledBy;
import com.spotshare.reservation.Reservation;
import com.spotshare.reservation.ReservationStatus;

/**
 * A driver's reservation as the driver sees it in lists and after booking.
 * Privacy-safe by construction: no exact address, space label, or parking
 * instructions — those are revealed only by the authorized detail view.
 * The {@code cancelledBy} field (null unless cancelled) lets the UI say
 * honestly who cancelled: "You cancelled" vs "Cancelled by the host".
 */
public record ReservationDto(
        UUID id,
        UUID spaceId,
        String areaLabel,
        String city,
        String state,
        ParkingType parkingType,
        OffsetDateTime arrival,
        OffsetDateTime departure,
        Integer hourlyRateCents,
        int totalCents,
        ReservationStatus status,
        String code,
        CancelledBy cancelledBy,
        OffsetDateTime createdAt) {

    public static ReservationDto from(Reservation r) {
        return new ReservationDto(
                r.getId(),
                r.getSpace().getId(),
                r.getSpace().getAreaLabel(),
                r.getSpace().getCity(),
                r.getSpace().getState(),
                r.getSpace().getParkingType(),
                r.getArrival(),
                r.getDeparture(),
                r.getHourlyRateCents(),
                r.getTotalCents(),
                r.getStatus(),
                r.getCode(),
                r.getCancelledBy(),
                r.getCreatedAt());
    }
}
