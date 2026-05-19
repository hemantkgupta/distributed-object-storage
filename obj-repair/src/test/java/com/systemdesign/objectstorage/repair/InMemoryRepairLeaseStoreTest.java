package com.systemdesign.objectstorage.repair;

import com.systemdesign.objectstorage.model.RepairLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InMemoryRepairLeaseStore}. Lease semantics:
 *   - acquire on free slot → returns lease
 *   - acquire on held slot by different owner → empty
 *   - release → frees slot
 *   - acquire on expired lease → returns new lease with new fencing token
 */
class InMemoryRepairLeaseStoreTest {

    private InMemoryRepairLeaseStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRepairLeaseStore();
    }

    @Test
    void firstAcquireSucceeds() {
        Optional<RepairLease> lease = store.tryAcquire("t-1", "worker-A", Duration.ofMinutes(5));
        assertThat(lease).isPresent();
        assertThat(lease.get().ownerId()).isEqualTo("worker-A");
        assertThat(lease.get().active()).isTrue();
    }

    @Test
    void doubleAcquireByDifferentOwnerFails() {
        store.tryAcquire("t-1", "worker-A", Duration.ofMinutes(5));
        Optional<RepairLease> second = store.tryAcquire("t-1", "worker-B", Duration.ofMinutes(5));
        assertThat(second).isEmpty();
    }

    @Test
    void releaseFreesLeaseForReacquisitionByOtherOwner() {
        RepairLease first = store.tryAcquire("t-1", "worker-A", Duration.ofMinutes(5)).orElseThrow();
        store.release("t-1", first.fencingToken());

        Optional<RepairLease> second = store.tryAcquire("t-1", "worker-B", Duration.ofMinutes(5));
        assertThat(second).isPresent();
        assertThat(second.get().ownerId()).isEqualTo("worker-B");
    }

    @Test
    void releaseWithStaleTokenIsNoOp() {
        RepairLease first = store.tryAcquire("t-1", "worker-A", Duration.ofMinutes(5)).orElseThrow();
        long staleToken = first.fencingToken() - 1;
        store.release("t-1", staleToken);

        // Lease still held by worker-A → worker-B is rejected.
        Optional<RepairLease> blocked = store.tryAcquire("t-1", "worker-B", Duration.ofMinutes(5));
        assertThat(blocked).isEmpty();
    }

    @Test
    void expiredLeaseCanBeReacquired() {
        RepairLease first = store.tryAcquire("t-1", "worker-A", Duration.ZERO).orElseThrow();
        // Duration.ZERO means expiresAt == acquiredAt → already expired the instant after.
        // The store's isExpiredAt check uses now.isBefore(expiresAt) so expiresAt == now
        // is treated as expired.
        Optional<RepairLease> second = store.tryAcquire("t-1", "worker-B", Duration.ofMinutes(5));
        assertThat(second).isPresent();
        assertThat(second.get().fencingToken()).isGreaterThan(first.fencingToken());
    }

    @Test
    void fencingTokensAreMonotonicallyIncreasing() {
        RepairLease a = store.tryAcquire("t-1", "w", Duration.ofMinutes(5)).orElseThrow();
        store.release("t-1", a.fencingToken());
        RepairLease b = store.tryAcquire("t-1", "w", Duration.ofMinutes(5)).orElseThrow();
        assertThat(b.fencingToken()).isGreaterThan(a.fencingToken());
    }

    @Test
    void loadActiveLeasesReturnsHeldLeases() {
        store.tryAcquire("t-1", "w-A", Duration.ofMinutes(5));
        store.tryAcquire("t-2", "w-B", Duration.ofMinutes(5));
        assertThat(store.loadActiveLeases()).hasSize(2);
    }

    @Test
    void releasedLeaseIsNotInActiveList() {
        RepairLease lease = store.tryAcquire("t-1", "w", Duration.ofMinutes(5)).orElseThrow();
        store.release("t-1", lease.fencingToken());
        assertThat(store.loadActiveLeases()).isEmpty();
    }
}
