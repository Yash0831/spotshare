package com.spotshare.search;

import java.util.List;

/**
 * Converts a human destination ("West Loop, Chicago") into coordinates.
 *
 * <p>The single permitted interface-with-one-implementation: geocoding and
 * map tiles are the one external vendor this product may need to swap, so
 * the provider sits behind an interface from day one. The first
 * implementation is Nominatim ({@link NominatimGeocodingProvider}).
 */
public interface GeocodingProvider {

    /**
     * Returns up to {@code limit} candidates for the query, best first.
     * Returns an empty list when nothing matches — never null.
     *
     * @throws com.spotshare.common.ApiException with code
     *         {@code GEOCODER_UNAVAILABLE} when the provider cannot be reached
     */
    List<GeocodeCandidate> search(String query, int limit);
}
