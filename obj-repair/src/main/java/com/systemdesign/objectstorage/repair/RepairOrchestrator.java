package com.systemdesign.objectstorage.repair;

import com.systemdesign.objectstorage.model.RepairBudget;
import com.systemdesign.objectstorage.model.RepairResult;
import com.systemdesign.objectstorage.model.RepairTask;

/**
 * Orchestrates the repair of a single object: locates surviving shards,
 * reconstructs the missing shard via erasure decoding, places the replacement
 * on a healthy node, and updates the metadata atomically.
 */
public interface RepairOrchestrator {

    /**
     * Attempts to repair one object.
     *
     * @param task   the repair task describing what needs fixing
     * @param budget constraints on I/O during this repair attempt
     * @return the outcome of the repair attempt
     */
    RepairResult repair(RepairTask task, RepairBudget budget);
}
