package com.spotshare.reservation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.spotshare.reservation.CancelledBy;
import com.spotshare.reservation.Reservation;
import com.spotshare.reservation.ReservationStatus;

/**
 * One row of the host's arrivals view (spec §12). Driver identity is
 * deliberately limited to first name + last initial (spec §11) — no
 * full last name, nothing more. The exact address is never included: the
 * host already knows their own address.
 *
 * Contact rule: while the arrival is CONFIRMED the host also gets the
 * driver's phone number, so the two can coordinate timing ("can you be
 * out by 6?"). The number is never exposed in discovery, and vanishes
 * from this view once the booking is cancelled or completed.
 */
public record HostArrivalDto(
        UUID id,
        String code,
        /** "Michael R." — first name plus last initial, never more. */
        String driverName,
        /**
         * The driver's phone — set only while the arrival is CONFIRMED.
         * Null for cancelled/completed arrivals.
         */
        String driverPhone,
        OffsetDateTime arrival,
        OffsetDateTime departure,
        ReservationStatus status,
        CancelledBy cancelledBy,
        /** The booked space — needed by the cross-space arrivals tab. */
        UUID spaceId,
        String spaceLabel) {

    public static HostArrivalDto from(Reservation r) {
        return new HostArrivalDto(
                r.getId(),
                r.getCode(),
                displayName(r.getDriver().getFirstName(), r.getDriver().getLastName()),
                r.getStatus() == ReservationStatus.CONFIRMED ? r.getDriver().getPhone() : null,
                r.getArrival(),
                r.getDeparture(),
                r.getStatus(),
                r.getCancelledBy(),
                r.getSpace().getId(),
                r.getSpace().getLabel());
    }

    private static String displayName(String firstName, String lastName) {
        if (lastName == null || lastName.isBlank()) {
            return firstName;
        }
        return firstName + " " + lastName.strip().charAt(0) + ".";
    }
}
