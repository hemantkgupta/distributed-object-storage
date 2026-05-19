package com.systemdesign.objectstorage.model;

/**
 * Budget constraints for a repair run, preventing repair storms from
 * overwhelming the cluster.
 *
 * @param maxConcurrentRepairs   maximum repair tasks executing simultaneously
 * @param maxShardsReadPerRun    maximum surviving shards fetched across all repairs in one cycle
 * @param maxShardsWrittenPerRun maximum replacement shards placed in one cycle
 */
public record RepairBudget(int maxConcurrentRepairs, int maxShardsReadPerRun, int maxShardsWrittenPerRun) {
    public RepairBudget {
        if (maxConcurrentRepairs <= 0) throw new IllegalArgumentException("max concurrent repairs must be positive");
        if (maxShardsReadPerRun <= 0) throw new IllegalArgumentException("max shards read must be positive");
        if (maxShardsWrittenPerRun <= 0) throw new IllegalArgumentException("max shards written must be positive");
    }

    public static RepairBudget defaults() {
        return new RepairBudget(4, 100, 25);
    }
}
