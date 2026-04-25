package com.systemdesign.objectstorage.model;

import java.time.Instant;

/**
 * A pending repair job: one object that needs a shard reconstructed.
 *
 * @param taskId            unique task identifier
 * @param objectId          the object_metadata.id of the affected object
 * @param missingShardIndex which shard index is missing or corrupt (-1 if unknown)
 * @param priority          repair urgency based on remaining shard count
 * @param createdAt         when the task was enqueued
 * @param nextRunAt         earliest time this task should be attempted
 * @param attempts          number of prior repair attempts
 */
public record RepairTask(
        String taskId,
        String objectId,
        int missingShardIndex,
        RepairPriority priority,
        Instant createdAt,
        Instant nextRunAt,
        int attempts
) {
    public RepairTask {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("task id must not be blank");
        if (objectId == null || objectId.isBlank()) throw new IllegalArgumentException("object id must not be blank");
        if (priority == null) throw new IllegalArgumentException("priority must not be null");
        if (createdAt == null) throw new IllegalArgumentException("created-at must not be null");
        if (nextRunAt == null) throw new IllegalArgumentException("next-run-at must not be null");
    }

    public boolean isDue(Instant now) {
        return !now.isBefore(nextRunAt);
    }

    public RepairTask withNextAttempt(Instant nextRunAt) {
        return new RepairTask(taskId, objectId, missingShardIndex, priority,
                createdAt, nextRunAt, attempts + 1);
    }
}
