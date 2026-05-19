package com.systemdesign.objectstorage.repair;

import com.systemdesign.objectstorage.model.RepairLease;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Distributed lease store that prevents two workers from repairing the same
 * object simultaneously. Uses monotonic fencing tokens to detect stale holders.
 */
public interface RepairLeaseStore {

    /**
     * Attempts to acquire a lease for the given task. Returns the lease if acquired,
     * or empty if another worker already holds a non-expired lease.
     *
     * @param taskId        the repair task to lock
     * @param ownerId       identifier of this worker
     * @param leaseDuration how long the lease should be held before expiry
     */
    Optional<RepairLease> tryAcquire(String taskId, String ownerId, Duration leaseDuration);

    /**
     * Releases a lease. The fencing token must match the lease that was acquired;
     * a stale token indicates the lease has already been reacquired by another worker.
     */
    void release(String taskId, long fencingToken);

    /** Returns all currently active (non-expired) leases. */
    List<RepairLease> loadActiveLeases();
}
