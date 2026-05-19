package com.systemdesign.objectstorage.repair;

import com.systemdesign.objectstorage.model.RepairLease;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory lease store for single-node testing. Uses ConcurrentHashMap
 * and monotonic fencing tokens to simulate distributed coordination semantics.
 */
public class InMemoryRepairLeaseStore implements RepairLeaseStore {

    private final ConcurrentHashMap<String, RepairLease> leases = new ConcurrentHashMap<>();
    private final AtomicLong tokenCounter = new AtomicLong(0);

    @Override
    public Optional<RepairLease> tryAcquire(String taskId, String ownerId, Duration leaseDuration) {
        Instant now = Instant.now();

        return Optional.ofNullable(leases.compute(taskId, (key, existing) -> {
            // No existing lease or expired → acquire
            if (existing == null || !existing.active() || existing.isExpiredAt(now)) {
                long token = tokenCounter.incrementAndGet();
                return new RepairLease(taskId, ownerId, token, now, now.plus(leaseDuration), true);
            }
            // Active, non-expired lease held by another owner → reject
            return existing;
        })).filter(lease -> lease.ownerId().equals(ownerId) && lease.active());
    }

    @Override
    public void release(String taskId, long fencingToken) {
        leases.computeIfPresent(taskId, (key, existing) -> {
            if (existing.fencingToken() == fencingToken) {
                return new RepairLease(taskId, existing.ownerId(), existing.fencingToken(),
                        existing.acquiredAt(), existing.expiresAt(), false);
            }
            return existing; // stale token — do nothing
        });
    }

    @Override
    public List<RepairLease> loadActiveLeases() {
        Instant now = Instant.now();
        return leases.values().stream()
                .filter(lease -> lease.active() && !lease.isExpiredAt(now))
                .toList();
    }
}
