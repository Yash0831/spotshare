package com.spotshare.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Cents-safe money math. Money travels as integer cents everywhere and is
 * only divided into dollars at the display edge — never floating point.
 */
public final class Money {

    private Money() {
    }

    /**
     * Prorated total for a parking period (spec §7): billable minutes ×
     * (hourly rate / 60), rounded HALF_UP to cents. A free share
     * ({@code hourlyRateCents == null}) costs nothing.
     */
    public static int proratedTotalCents(Integer hourlyRateCents, Instant arrival, Instant departure) {
        if (hourlyRateCents == null) {
            return 0;
        }
        long minutes = java.time.Duration.between(arrival, departure).toMinutes();
        return BigDecimal.valueOf(hourlyRateCents)
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }
}
