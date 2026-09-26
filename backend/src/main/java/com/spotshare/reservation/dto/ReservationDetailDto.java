package com.spotshare.reservation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.spotshare.parking.ParkingType;
import com.spotshare.reservation.Reservation;
import com.spotshare.reservation.ReservationStatus;

/**
 * The authorized reservation detail — the ONLY response that carries the
 * exact address, space number, and parking/access instructions, and only
 * for the reservation's driver (while CONFIRMED) or the space's host.
 *
 * Contact rule: while the reservation is CONFIRMED, each party sees the
 * <em>counterparty's</em> phone number so host and driver can coordinate
 * ("I'm running late", "can you be out by 6?"). The numbers are never
 * exposed in discovery or in reservation summaries, and vanish once the
 * booking is over — booking is the act that reveals, and un-booking
 * revokes.
 */
public record ReservationDetailDto(
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
        /** The space number/label, e.g. "B17". */
        String spaceLabel,
        /** Exact street address — revealed only here. */
        String address,
        String zipCode,
        /** Private parking/access instructions — revealed only here. */
        String parkingInstructions,
        /** "Michael R." — first name plus last initial. */
        String hostName,
        String driverName,
        /**
         * The host's phone — set only for the driver caller while the
         * reservation is CONFIRMED. Null for the host (it's their own
         * number) and once the booking ends.
         */
        String hostPhone,
        /**
         * The driver's phone — set only for the host caller while the
         * reservation is CONFIRMED. Null for the driver (it's their own
         * number) and once the booking ends.
         */
        String driverPhone,
        OffsetDateTime createdAt) {

    public static ReservationDetailDto from(Reservation r, UUID callerId) {
        var space = r.getSpace();
        boolean isHost = space.getHost().getId().equals(callerId);
        boolean live = r.getStatus() == ReservationStatus.CONFIRMED;
        return new ReservationDetailDto(
                r.getId(),
                space.getId(),
                space.getAreaLabel(),
                space.getCity(),
                space.getState(),
                space.getParkingType(),
                r.getArrival(),
                r.getDeparture(),
                r.getHourlyRateCents(),
                r.getTotalCents(),
                r.getStatus(),
                r.getCode(),
                space.getLabel(),
                space.getAddress(),
                space.getZipCode(),
                space.getParkingInstructions(),
                displayName(space.getHost().getFirstName(), space.getHost().getLastName()),
                displayName(r.getDriver().getFirstName(), r.getDriver().getLastName()),
                !isHost && live ? space.getHost().getPhone() : null,
                isHost && live ? r.getDriver().getPhone() : null,
                r.getCreatedAt());
    }

    private static String displayName(String firstName, String lastName) {
        if (lastName == null || lastName.isBlank()) {
            return firstName;
        }
        return firstName + " " + lastName.strip().charAt(0) + ".";
    }
}
