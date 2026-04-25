package com.systemdesign.objectstorage.model;

import com.systemdesign.objectstorage.storage.ShardId;

import java.time.Instant;

/**
 * Result of scrubbing a single shard.
 *
 * @param shardId    the shard that was checked
 * @param outcome    whether it was clean, corrupt, missing, or unreadable
 * @param scrubbedAt when the check was performed
 * @param detail     optional detail (e.g., "expected checksum abc, got xyz")
 */
public record ScrubResult(ShardId shardId, ScrubOutcome outcome, Instant scrubbedAt, String detail) {
    public ScrubResult {
        if (shardId == null) throw new IllegalArgumentException("shard id must not be null");
        if (outcome == null) throw new IllegalArgumentException("outcome must not be null");
        if (scrubbedAt == null) throw new IllegalArgumentException("scrubbed-at must not be null");
    }

    public boolean isHealthy() {
        return outcome == ScrubOutcome.CLEAN;
    }
}
