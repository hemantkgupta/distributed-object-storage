package com.systemdesign.objectstorage.model;

import java.time.Instant;

/**
 * A distributed lease that prevents two workers from repairing the same object
 * simultaneously. Uses monotonic fencing tokens to detect stale lease holders.
 *
 * @param taskId       the repair task this lease covers
 * @param ownerId      identifier of the worker holding the lease
 * @param fencingToken monotonic counter — incremented on each acquire; stale holders have lower tokens
 * @param acquiredAt   when the lease was acquired
 * @param expiresAt    when the lease expires if not released
 * @param active       whether the lease is currently held
 */
public record RepairLease(
        String taskId,
        String ownerId,
        long fencingToken,
        Instant acquiredAt,
        Instant expiresAt,
        boolean active
) {
    public RepairLease {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("task id must not be blank");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("owner id must not be blank");
        if (fencingToken < 0) throw new IllegalArgumentException("fencing token must not be negative");
        if (acquiredAt == null) throw new IllegalArgumentException("acquired-at must not be null");
        if (expiresAt == null) throw new IllegalArgumentException("expires-at must not be null");
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
