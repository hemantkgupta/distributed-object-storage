package com.systemdesign.objectstorage.scrubber;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the {@link ScrubBudget} value record. Both max-shards and max-duration
 * are enforced; the budget is the I/O firewall that keeps a scrub pass from
 * starving foreground traffic.
 */
class ScrubBudgetTest {

    @Test
    void accessorsExposeConfiguredValues() {
        ScrubBudget budget = new ScrubBudget(100, Duration.ofMinutes(5));
        assertThat(budget.maxShardsPerRun()).isEqualTo(100);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void defaultsExposeReasonableValues() {
        ScrubBudget defaults = ScrubBudget.defaults();
        assertThat(defaults.maxShardsPerRun()).isPositive();
        assertThat(defaults.maxDuration()).isPositive();
    }

    @Test
    void zeroMaxShardsIsRejected() {
        assertThatThrownBy(() -> new ScrubBudget(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeMaxShardsIsRejected() {
        assertThatThrownBy(() -> new ScrubBudget(-5, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeDurationIsRejected() {
        assertThatThrownBy(() -> new ScrubBudget(10, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDurationIsRejected() {
        assertThatThrownBy(() -> new ScrubBudget(10, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroDurationIsAllowed() {
        // Zero duration is a no-op pass, not invalid — useful for "drain only" semantics.
        ScrubBudget budget = new ScrubBudget(10, Duration.ZERO);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ZERO);
    }
}
