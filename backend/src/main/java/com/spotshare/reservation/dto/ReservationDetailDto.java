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
        OffsetDateTime createdAt) {

    public static ReservationDetailDto from(Reservation r) {
        var space = r.getSpace();
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
                r.getCreatedAt());
    }

    private static String displayName(String firstName, String lastName) {
        if (lastName == null || lastName.isBlank()) {
            return firstName;
        }
        return firstName + " " + lastName.strip().charAt(0) + ".";
    }
}
