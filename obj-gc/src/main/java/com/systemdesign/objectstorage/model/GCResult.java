package com.systemdesign.objectstorage.model;

import java.time.Instant;

/**
 * Result of a garbage collection run.
 *
 * @param orphansQuarantined      shards with no metadata reference moved to quarantine
 * @param orphansPurged           quarantined shards past grace period permanently deleted
 * @param abandonedUploadsAborted INITIATED multipart uploads past max age auto-aborted
 * @param completedAt             when the GC run finished
 */
public record GCResult(int orphansQuarantined, int orphansPurged, int abandonedUploadsAborted, Instant completedAt) {
    public GCResult {
        if (completedAt == null) throw new IllegalArgumentException("completed-at must not be null");
    }

    public int totalCleaned() {
        return orphansQuarantined + orphansPurged + abandonedUploadsAborted;
    }
}
