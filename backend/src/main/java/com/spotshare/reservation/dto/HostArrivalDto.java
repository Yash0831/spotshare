package com.spotshare.reservation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.spotshare.reservation.CancelledBy;
import com.spotshare.reservation.Reservation;
import com.spotshare.reservation.ReservationStatus;

/**
 * One row of the host's arrivals view (spec §12). Driver identity is
 * deliberately limited to first name + last initial (spec §11) — no
 * contact details, no full last name, nothing more. The exact address is
 * never included: the host already knows their own address.
 */
public record HostArrivalDto(
        UUID id,
        String code,
        /** "Michael R." — first name plus last initial, never more. */
        String driverName,
        OffsetDateTime arrival,
        OffsetDateTime departure,
        ReservationStatus status,
        CancelledBy cancelledBy) {

    public static HostArrivalDto from(Reservation r) {
        return new HostArrivalDto(
                r.getId(),
                r.getCode(),
                displayName(r.getDriver().getFirstName(), r.getDriver().getLastName()),
                r.getArrival(),
                r.getDeparture(),
                r.getStatus(),
                r.getCancelledBy());
    }

    private static String displayName(String firstName, String lastName) {
        if (lastName == null || lastName.isBlank()) {
            return firstName;
        }
        return firstName + " " + lastName.strip().charAt(0) + ".";
    }
}
