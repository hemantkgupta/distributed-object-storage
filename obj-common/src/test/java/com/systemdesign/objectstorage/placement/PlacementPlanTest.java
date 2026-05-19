package com.systemdesign.objectstorage.placement;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link PlacementPlan} record: construction, accessors,
 * defensive copies, and validation.
 */
class PlacementPlanTest {

    @Test
    void shardAssignmentsAreExposed() {
        Map<Integer, Integer> assignments = Map.of(0, 1, 1, 2, 2, 3);
        PlacementPlan plan = new PlacementPlan(assignments, List.of());
        assertThat(plan.shardAssignments()).containsAllEntriesOf(assignments);
    }

    @Test
    void warningsListIsExposed() {
        PlacementPlan plan = new PlacementPlan(Map.of(0, 1), List.of("node 1 above 80% capacity"));
        assertThat(plan.warnings()).containsExactly("node 1 above 80% capacity");
    }

    @Test
    void nullWarningsBecomesEmptyList() {
        PlacementPlan plan = new PlacementPlan(Map.of(0, 1), null);
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void warningsAreDefensivelyCopied() {
        java.util.ArrayList<String> mutable = new java.util.ArrayList<>();
        mutable.add("first");
        PlacementPlan plan = new PlacementPlan(Map.of(0, 1), mutable);
        mutable.add("second");
        assertThat(plan.warnings()).containsExactly("first");
    }

    @Test
    void emptyAssignmentsAreRejected() {
        assertThatThrownBy(() -> new PlacementPlan(Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAssignmentsAreRejected() {
        assertThatThrownBy(() -> new PlacementPlan(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planWithAssignmentsIsValid() {
        PlacementPlan plan = new PlacementPlan(Map.of(0, 1, 1, 2), List.of());
        assertThat(plan.isValid()).isTrue();
    }
}
