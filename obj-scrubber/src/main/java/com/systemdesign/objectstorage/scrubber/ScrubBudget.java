package com.systemdesign.objectstorage.scrubber;

import java.time.Duration;

/**
 * Budget for a single scrub pass — limits I/O impact on production traffic.
 *
 * @param maxShardsPerRun maximum number of shards to verify in one pass
 * @param maxDuration     wall-clock time limit for the scrub pass
 */
public record ScrubBudget(int maxShardsPerRun, Duration maxDuration) {
    public ScrubBudget {
        if (maxShardsPerRun <= 0) throw new IllegalArgumentException("max shards per run must be positive");
        if (maxDuration == null || maxDuration.isNegative()) throw new IllegalArgumentException("max duration must not be null or negative");
    }

    public static ScrubBudget defaults() {
        return new ScrubBudget(500, Duration.ofMinutes(10));
    }
}
