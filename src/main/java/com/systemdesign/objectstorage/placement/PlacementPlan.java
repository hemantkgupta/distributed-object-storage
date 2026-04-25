package com.systemdesign.objectstorage.placement;

import java.util.List;
import java.util.Map;

/**
 * The result of a placement decision: which node gets which shard,
 * plus any warnings about constraint violations or capacity issues.
 *
 * @param shardAssignments shard index → node ID
 * @param warnings         non-fatal issues (e.g., "node 3 is above 80% capacity")
 */
public record PlacementPlan(Map<Integer, Integer> shardAssignments, List<String> warnings) {
    public PlacementPlan {
        if (shardAssignments == null || shardAssignments.isEmpty()) {
            throw new IllegalArgumentException("shard assignments must not be empty");
        }
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        shardAssignments = Map.copyOf(shardAssignments);
    }

    /** Returns true if placement succeeded with no fatal constraint violations. */
    public boolean isValid() {
        return !shardAssignments.isEmpty();
    }
}
