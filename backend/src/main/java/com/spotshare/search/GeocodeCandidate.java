package com.spotshare.search;

/**
 * One geocoding result: a human-readable place name and its coordinates.
 * Coordinates are the provider's best estimate for the named place, not a
 * private address — nothing here is sensitive.
 */
public record GeocodeCandidate(
        String displayName,
        double latitude,
        double longitude
) {
}
