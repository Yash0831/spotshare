/**
 * Geo services. V1 deliberately has NO PostGIS: latitude/longitude stored as
 * double precision, nearby search via the haversine formula in SQL. All geo
 * access sits behind a GeoService interface so PostGIS can replace the
 * implementation later (documented in docs/architecture.md in Phase 10).
 * Geocoding goes through a GeocodingService interface with a Nominatim
 * implementation. (Phase 3: services + pricing.)
 */
package com.spotshare.geo;
