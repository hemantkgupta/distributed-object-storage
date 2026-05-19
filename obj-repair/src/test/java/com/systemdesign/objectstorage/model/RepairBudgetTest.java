package com.systemdesign.objectstorage.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the {@link RepairBudget} record: positive-only invariants and accessors.
 */
class RepairBudgetTest {

    @Test
    void accessorsExposeConfiguredValues() {
        RepairBudget budget = new RepairBudget(4, 100, 25);
        assertThat(budget.maxConcurrentRepairs()).isEqualTo(4);
        assertThat(budget.maxShardsReadPerRun()).isEqualTo(100);
        assertThat(budget.maxShardsWrittenPerRun()).isEqualTo(25);
    }

    @Test
    void defaultsAreReasonable() {
        RepairBudget defaults = RepairBudget.defaults();
        assertThat(defaults.maxConcurrentRepairs()).isPositive();
        assertThat(defaults.maxShardsReadPerRun()).isPositive();
        assertThat(defaults.maxShardsWrittenPerRun()).isPositive();
    }

    @Test
    void zeroConcurrentIsRejected() {
        assertThatThrownBy(() -> new RepairBudget(0, 100, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeReadsAreRejected() {
        assertThatThrownBy(() -> new RepairBudget(4, -1, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeWritesAreRejected() {
        assertThatThrownBy(() -> new RepairBudget(4, 100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeBudgetCapsConcurrentRepairsIndirectly() {
        // Doc the relationship: even if maxConcurrentRepairs is huge,
        // maxShardsWrittenPerRun is the harder ceiling.
        RepairBudget budget = new RepairBudget(1000, 1000, 5);
        assertThat(budget.maxShardsWrittenPerRun()).isEqualTo(5);
    }
}
