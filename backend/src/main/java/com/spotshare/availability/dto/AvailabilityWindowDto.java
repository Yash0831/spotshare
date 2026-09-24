package com.spotshare.availability.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.spotshare.availability.WindowSource;

/**
 * A share window as the host sees it. {@code live} is derived from the
 * timestamps at read time — expiry needs no stored status.
 */
public record AvailabilityWindowDto(
        UUID id,
        UUID spaceId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        WindowSource source,
        /** Integer cents per hour; {@code null} means the share is free. */
        Integer hourlyRateCents,
        /** True while {@code startsAt <= now < endsAt}. */
        boolean live,
        OffsetDateTime createdAt
) {
}
