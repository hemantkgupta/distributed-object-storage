package com.systemdesign.objectstorage.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link RepairPriority} enum. CRITICAL is ahead of HIGH which is
 * ahead of NORMAL — declaration order is the priority order used by the in-memory
 * repair task queue's natural sort.
 */
class RepairPriorityTest {

    @Test
    void criticalSortsBeforeHigh() {
        assertThat(RepairPriority.CRITICAL.compareTo(RepairPriority.HIGH)).isNegative();
    }

    @Test
    void highSortsBeforeNormal() {
        assertThat(RepairPriority.HIGH.compareTo(RepairPriority.NORMAL)).isNegative();
    }

    @Test
    void criticalSortsBeforeNormal() {
        assertThat(RepairPriority.CRITICAL.compareTo(RepairPriority.NORMAL)).isNegative();
    }

    @Test
    void sortingProducesCriticalHighNormalOrder() {
        List<RepairPriority> shuffled = new ArrayList<>(List.of(
                RepairPriority.NORMAL, RepairPriority.HIGH, RepairPriority.CRITICAL));
        Collections.sort(shuffled);
        assertThat(shuffled).containsExactly(
                RepairPriority.CRITICAL, RepairPriority.HIGH, RepairPriority.NORMAL);
    }

    @Test
    void enumHasExactlyThreeValues() {
        assertThat(RepairPriority.values()).hasSize(3);
    }
}
