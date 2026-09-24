package com.spotshare.common;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Prorated parking totals: minutes × hourly rate / 60, rounded HALF_UP to
 * cents. Money stays integer cents — never floating point.
 */
class MoneyTest {

    private static final Instant T0 = Instant.parse("2026-09-24T12:00:00Z");

    @Test
    void exactHourBillsExactly() {
        assertThat(Money.proratedTotalCents(300, T0, T0.plusSeconds(3600))).isEqualTo(300);
    }

    @Test
    void proratesPartialHours() {
        // 5 h × $3 = $15.00
        assertThat(Money.proratedTotalCents(300, T0, T0.plusSeconds(5 * 3600))).isEqualTo(1500);
        // 90 min × $3 = $4.50
        assertThat(Money.proratedTotalCents(300, T0, T0.plusSeconds(90 * 60))).isEqualTo(450);
    }

    @Test
    void roundsHalfUp() {
        // 1 min × $1/hr = 1.666…¢ → 2¢
        assertThat(Money.proratedTotalCents(100, T0, T0.plusSeconds(60))).isEqualTo(2);
        // 7 min × $2.50/hr = 29.166…¢ → 29¢
        assertThat(Money.proratedTotalCents(250, T0, T0.plusSeconds(7 * 60))).isEqualTo(29);
        // 1 min × $3/hr = 5¢ exactly
        assertThat(Money.proratedTotalCents(300, T0, T0.plusSeconds(60))).isEqualTo(5);
    }

    @Test
    void freeShareCostsNothing() {
        assertThat(Money.proratedTotalCents(null, T0, T0.plusSeconds(3 * 3600))).isZero();
    }

    @Test
    void zeroMinutesCostsNothing() {
        assertThat(Money.proratedTotalCents(300, T0, T0)).isZero();
    }
}
