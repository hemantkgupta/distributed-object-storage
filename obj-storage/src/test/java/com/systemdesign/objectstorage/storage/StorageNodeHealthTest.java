package com.systemdesign.objectstorage.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the {@link StorageNodeHealth} record's derived metrics.
 */
class StorageNodeHealthTest {

    @Test
    void freeBytesIsCapacityMinusUsed() {
        StorageNodeHealth h = new StorageNodeHealth(1, 1_000L, 250L, 4, true);
        assertThat(h.freeBytes()).isEqualTo(750L);
    }

    @Test
    void usageRatioIsUsedOverCapacity() {
        StorageNodeHealth h = new StorageNodeHealth(1, 1_000L, 250L, 4, true);
        assertThat(h.usageRatio()).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void zeroCapacityNodeReportsUsageRatioOne() {
        // Defensive: division-by-zero guard returns 1.0 (treat as "full").
        StorageNodeHealth h = new StorageNodeHealth(1, 0L, 0L, 0, false);
        assertThat(h.usageRatio()).isEqualTo(1.0);
    }

    @Test
    void shardCountAccessorReturnsConstructorValue() {
        StorageNodeHealth h = new StorageNodeHealth(2, 1_000L, 0L, 42, true);
        assertThat(h.shardCount()).isEqualTo(42);
    }

    @Test
    void healthyFlagIsExposed() {
        StorageNodeHealth healthy = new StorageNodeHealth(1, 1L, 0L, 0, true);
        StorageNodeHealth unhealthy = new StorageNodeHealth(1, 1L, 0L, 0, false);
        assertThat(healthy.healthy()).isTrue();
        assertThat(unhealthy.healthy()).isFalse();
    }

    @Test
    void fullCapacityReportsRatioOne() {
        StorageNodeHealth h = new StorageNodeHealth(1, 1_000L, 1_000L, 1, true);
        assertThat(h.usageRatio()).isCloseTo(1.0, within(1e-9));
        assertThat(h.freeBytes()).isZero();
    }
}
