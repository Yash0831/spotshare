package com.spotshare.search;

import java.util.List;

/**
 * One page of discovery results, nearest first. {@code hasMore} is true
 * when another page exists (the query fetches one row past the page size
 * instead of running a separate COUNT).
 */
public record SearchResponseDto(
        List<PublicSpaceDto> results,
        int page,
        int size,
        boolean hasMore
) {
}
