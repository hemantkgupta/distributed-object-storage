package com.systemdesign.objectstorage.gc;

import java.time.Duration;

/**
 * Policy for garbage collection.
 *
 * @param orphanGracePeriod       how long an unreferenced shard must exist before quarantine/purge
 * @param abandonedUploadMaxAge   max age of an INITIATED multipart upload before auto-abort
 */
public record GCPolicy(Duration orphanGracePeriod, Duration abandonedUploadMaxAge) {
    public GCPolicy {
        if (orphanGracePeriod == null || orphanGracePeriod.isNegative())
            throw new IllegalArgumentException("orphan grace period must not be null or negative");
        if (abandonedUploadMaxAge == null || abandonedUploadMaxAge.isNegative())
            throw new IllegalArgumentException("abandoned upload max age must not be null or negative");
    }

    public static GCPolicy defaults() {
        return new GCPolicy(Duration.ofHours(24), Duration.ofDays(7));
    }
}
