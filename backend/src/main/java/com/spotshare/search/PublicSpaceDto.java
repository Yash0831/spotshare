package com.spotshare.search;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.spotshare.parking.ParkingType;
import com.spotshare.parking.dto.PhotoDto;

/**
 * The privacy-safe view of a parking space used by discovery. This is the
 * DTO where the spec's privacy rules (§11) are enforced: it structurally
 * cannot carry an exact address, a space label/number, parking instructions,
 * or any host contact details, because it has no fields for them.
 *
 * <ul>
 *   <li>Location is approximate: coordinates rounded to 3 decimals (~110 m)
 *       plus the host-entered {@code areaLabel} and city/state.</li>
 *   <li>The host is identified as "first name + last initial"
 *       ("Michael R.").</li>
 *   <li>{@code hourlyRateCents} is {@code null} for a free share;
 *       {@code estimatedTotalCents} is the prorated price for the searched
 *       period (billable minutes × rate / 60, rounded HALF_UP to cents).</li>
 *   <li>Photos are the public content URLs — photos carry no private
 *       location data by design.</li>
 * </ul>
 */
public record PublicSpaceDto(
        UUID id,
        String areaLabel,
        String city,
        String state,
        ParkingType parkingType,
        String description,
        List<String> vehicleSizes,
        Integer heightLimitInches,
        boolean covered,
        boolean evCharging,
        /** Approximate latitude, rounded to 3 decimals. */
        double approxLatitude,
        /** Approximate longitude, rounded to 3 decimals. */
        double approxLongitude,
        /** Distance from the search point in miles, one decimal. */
        double distanceMiles,
        /** "Michael R." — first name plus last initial. */
        String hostName,
        /** Integer cents per hour; null = free. */
        Integer hourlyRateCents,
        /** Prorated total for the searched period, in cents. */
        int estimatedTotalCents,
        OffsetDateTime windowStartsAt,
        OffsetDateTime windowEndsAt,
        List<PhotoDto> photos
) {
}
