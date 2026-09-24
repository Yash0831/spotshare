package com.spotshare.search;

import java.math.BigDecimal;
import java.time.Instant;

import com.spotshare.parking.VehicleSize;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Query parameters for {@code GET /api/v1/spaces/search}, bound by Spring
 * from the query string. Single-field rules live here as Bean Validation;
 * cross-field rules (arrival before departure, arrival not in the past)
 * live in {@link SearchService}, which throws the friendly 422s.
 *
 * <p>Radius is in <strong>miles</strong> (spec §10): default 3, adjustable
 * 0.5–25. Distances in the response are miles, one decimal.
 */
public record SearchRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
        /** Search radius in miles; null → 3. */
        @DecimalMin("0.5") @DecimalMax("25") Double radiusMiles,
        /** Period start; must be before departure and not in the past. */
        @NotNull Instant arrival,
        /** Period end; the space's share window must contain [arrival, departure). */
        @NotNull Instant departure,
        /** Maximum hourly rate in dollars, e.g. 12.50. Free spaces always match. */
        @DecimalMin("0.01") @Digits(integer = 6, fraction = 2) BigDecimal maxPrice,
        Boolean covered,
        Boolean evCharging,
        VehicleSize vehicleSize,
        /** Zero-based page; null → 0. */
        @Min(0) Integer page,
        /** Page size; null → 20, max 100. */
        @Min(1) @Max(100) Integer size
) {
}
