package com.spotshare.search;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the PostGIS search query: the space's public columns plus the
 * cheapest share window fully containing the searched period, the host's
 * name, and the distance in meters. Package-visible — the service maps this
 * to the privacy-safe {@link PublicSpaceDto}.
 */
record SearchRow(
        UUID spaceId,
        String areaLabel,
        String city,
        String state,
        String parkingType,
        String description,
        String[] vehicleSizes,
        Integer heightLimitInches,
        boolean covered,
        boolean evCharging,
        double latitude,
        double longitude,
        String hostFirstName,
        String hostLastName,
        OffsetDateTime windowStartsAt,
        OffsetDateTime windowEndsAt,
        Integer hourlyRateCents,
        double distanceMeters
) {
}
