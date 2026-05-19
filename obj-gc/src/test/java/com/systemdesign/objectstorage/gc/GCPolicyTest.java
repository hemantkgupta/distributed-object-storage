package com.systemdesign.objectstorage.gc;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link GCPolicy} record: accessors, defaults, and invariants.
 */
class GCPolicyTest {

    @Test
    void accessorsExposeConfiguredValues() {
        GCPolicy policy = new GCPolicy(Duration.ofHours(2), Duration.ofDays(3));
        assertThat(policy.orphanGracePeriod()).isEqualTo(Duration.ofHours(2));
        assertThat(policy.abandonedUploadMaxAge()).isEqualTo(Duration.ofDays(3));
    }

    @Test
    void defaultsAreReasonable() {
        GCPolicy defaults = GCPolicy.defaults();
        // 24h orphan grace and 7d upload max age — protect against premature deletion.
        assertThat(defaults.orphanGracePeriod()).isEqualTo(Duration.ofHours(24));
        assertThat(defaults.abandonedUploadMaxAge()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void zeroOrphanGracePeriodIsAllowed() {
        // Used in test/dry-run scenarios — purge immediately when scan finds an orphan.
        GCPolicy policy = new GCPolicy(Duration.ZERO, Duration.ofDays(1));
        assertThat(policy.orphanGracePeriod()).isEqualTo(Duration.ZERO);
    }

    @Test
    void negativeGracePeriodIsRejected() {
        assertThatThrownBy(() -> new GCPolicy(Duration.ofMinutes(-1), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeUploadMaxAgeIsRejected() {
        assertThatThrownBy(() -> new GCPolicy(Duration.ofMinutes(10), Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDurationsAreRejected() {
        assertThatThrownBy(() -> new GCPolicy(null, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GCPolicy(Duration.ofMinutes(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
