package com.systemdesign.objectstorage.model;

import java.time.Instant;

/**
 * Outcome of a single repair attempt.
 *
 * @param taskId         the repair task that was executed
 * @param outcome        whether the repair succeeded, failed, or was blocked
 * @param shardsRead     number of surviving shards fetched during reconstruction
 * @param shardsWritten  number of replacement shards placed (0 or 1 typically)
 * @param completedAt    when the repair attempt finished
 * @param detail         human-readable detail (e.g., "reconstructed shard 2 on node 7")
 */
public record RepairResult(
        String taskId,
        RepairOutcome outcome,
        int shardsRead,
        int shardsWritten,
        Instant completedAt,
        String detail
) {
    public RepairResult {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("task id must not be blank");
        if (outcome == null) throw new IllegalArgumentException("outcome must not be null");
        if (completedAt == null) throw new IllegalArgumentException("completed-at must not be null");
    }

    public boolean isSuccess() {
        return outcome == RepairOutcome.REPAIRED;
    }
}
