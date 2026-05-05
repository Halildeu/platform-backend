package com.serban.notify.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackoffCalculator unit test (Codex 019dfa47 Q3 absorb).
 */
class BackoffCalculatorTest {

    @Test
    void firstAttemptUsesInitialDelay() {
        // No jitter for deterministic
        BackoffCalculator calc = new BackoffCalculator(30000, 2.5, 3600000, 0.0);
        Duration d = calc.computeDelay(1);
        assertThat(d.toMillis()).isEqualTo(30000);
    }

    @Test
    void exponentialGrowth() {
        BackoffCalculator calc = new BackoffCalculator(30000, 2.5, 3600000, 0.0);
        // attempt N: 30000 * 2.5^(N-1)
        assertThat(calc.computeDelay(1).toMillis()).isEqualTo(30000);
        assertThat(calc.computeDelay(2).toMillis()).isEqualTo(75000);
        assertThat(calc.computeDelay(3).toMillis()).isEqualTo(187500);
        assertThat(calc.computeDelay(4).toMillis()).isEqualTo(468750);
        assertThat(calc.computeDelay(5).toMillis()).isEqualTo(1171875);
    }

    @Test
    void jitterStaysWithinRatio() {
        BackoffCalculator calc = new BackoffCalculator(30000, 2.5, 3600000, 0.25);
        // ±25% bound around 30000 → [22500, 37500]
        for (int i = 0; i < 100; i++) {
            long ms = calc.computeDelay(1).toMillis();
            assertThat(ms).isBetween(22500L, 37500L);
        }
    }

    @Test
    void capLimitsLargeAttempts() {
        BackoffCalculator calc = new BackoffCalculator(30000, 2.5, 60000, 0.0);
        // attempt 5 normally 1171875ms; cap at 60000ms
        long ms = calc.computeDelay(5).toMillis();
        assertThat(ms).isEqualTo(60000);
    }

    @Test
    void capWithJitterStaysBelowCapPlusJitter() {
        BackoffCalculator calc = new BackoffCalculator(30000, 2.5, 60000, 0.25);
        for (int i = 0; i < 100; i++) {
            long ms = calc.computeDelay(10).toMillis();
            // Cap = 60000; ±25% jitter on cap → [45000, 75000]
            assertThat(ms).isBetween(45000L, 75000L);
        }
    }

    @Test
    void zeroAttemptDefaultsToOne() {
        BackoffCalculator calc = new BackoffCalculator(30000, 2.5, 3600000, 0.0);
        assertThat(calc.computeDelay(0).toMillis()).isEqualTo(30000);
        assertThat(calc.computeDelay(-5).toMillis()).isEqualTo(30000);
    }

    @Test
    void noJitterDeterministic() {
        BackoffCalculator calc = new BackoffCalculator(1000, 2, 100000, 0.0);
        long a = calc.computeDelay(3).toMillis();
        long b = calc.computeDelay(3).toMillis();
        assertThat(a).isEqualTo(b).isEqualTo(4000);  // 1000 * 2^2
    }

    @Test
    void neverNegative() {
        BackoffCalculator calc = new BackoffCalculator(1000, 2, 100000, 0.99);
        for (int i = 0; i < 100; i++) {
            assertThat(calc.computeDelay(1).toMillis()).isNotNegative();
        }
    }
}
