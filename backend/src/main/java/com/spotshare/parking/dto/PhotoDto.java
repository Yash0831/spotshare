package com.spotshare.parking.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A space photo. The raw bytes are served from
 * {@code GET /spaces/{spaceId}/photos/{photoId}/content}; this DTO carries
 * the metadata plus that URL (never the bytes themselves).
 */
public record PhotoDto(
        UUID id,
        String contentType,
        int sortOrder,
        String contentUrl,
        OffsetDateTime createdAt
) {
}
