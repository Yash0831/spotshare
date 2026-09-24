/**
 * Driver discovery: nearby parking search and geocoding.
 *
 * <p>Search is PostGIS-backed (radius + KNN ordering) and returns only
 * privacy-safe DTOs — exact addresses, space labels, parking instructions,
 * and host contact details can never leave the backend here. Reservation
 * conflict filtering arrives in Phase 5; until then a space listed here is
 * "shared for the whole period" but may still be reservable by another
 * driver first.
 *
 * <p>{@link GeocodingProvider} is the one interface-with-one-implementation
 * the spec allows, because swapping the map/geocoding vendor later is a
 * genuine, known need.
 */
package com.spotshare.search;
