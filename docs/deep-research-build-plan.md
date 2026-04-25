# Deep Research Build Plan — Object Storage Durability Layer

## Status

**Plan created**: 2026-04-26
**Last updated**: 2026-04-26
**Research source**: Deep research report on S3/MinIO/Ceph durability architecture
**Goal**: Bring the object storage codebase to the same quality as the KV store by building the missing integrity, repair, and operational layers.

### Progress

| Phase | Status | Files | LOC |
|---|---|---|---|
| Phase 1–3 (Core Gateway + EC + Multipart) | ✅ Done (pre-existing) | 12 | 1,115 |
| Phase 4 (Foundation Contracts + Domain Model) | ✅ Done | +19 | +720 |
| Phase 5 (Scrubber) | ✅ Done | +4 | +200 |
| Phase 6 (Repair Orchestrator + Leases + Queue) | ✅ Done | +6 | +380 |
| Phase 7 (GC Service) | ✅ Done | +3 | +120 |
| Phase 8 (Refactor Gateway Controller) | ⬜ Not started | — | — |
| Phase 9 (Blog + Docs update) | ⬜ Not started | — | — |
| **Current totals** | | **42 source + 2 test** | **2,534 source + 468 test** |

Codebase went from **12 files / 1,115 LOC / 0 interfaces** to **42 files / 2,534 LOC / 10 interfaces / 16 records+enums**.

## Key Insights from Research

1. **Build state and contracts first, then verification, then repair.** (Ceph/MinIO pattern)
2. **Topology-aware placement is P0** — ring walk is not enough; must enforce failure domain constraints.
3. **Scrubber must be persistent** — scan cursor, not restartless scan-from-beginning.
4. **Repair needs fenced leases** — prevent two nodes repairing the same shard simultaneously.
5. **GC must quarantine before purge** — immediate delete can destroy the only recoverable copy.
6. **Metadata updates on repair must be CAS (compare-and-swap)** — prevent stale authority overwriting.
7. **Do NOT build**: LRC/CLAY codes, geo-distributed shard striping, S3 storage classes, or MTTDL simulator for MVP.

## Build Phases

### Phase 4: Foundation Contracts and Domain Model
**Goal**: Define the interface contracts and domain types that the scrubber, repair, and GC layers depend on.
**Depends on**: Existing Phases 1–3 (done)

**4a. Storage Node Contract**

Create `StorageNode` interface to replace direct `NodeStorageService` calls:

```java
public interface StorageNode {
    void writeShard(ShardId shardId, byte[] data);
    byte[] readShard(ShardId shardId);
    void deleteShard(ShardId shardId);
    boolean shardExists(ShardId shardId);
    List<ShardId> listShards();              // NEW: inventory for scrubber
    byte[] shardChecksum(ShardId shardId);   // NEW: checksum for scrubber
    StorageNodeHealth health();              // NEW: capacity, status
}
```

Files to create:
- `src/main/java/.../storage/StorageNode.java` — interface
- `src/main/java/.../storage/ShardId.java` — record (nodeId + physicalShardId)
- `src/main/java/.../storage/StorageNodeHealth.java` — record (nodeId, capacityBytes, usedBytes, shardCount, healthy)
- `src/main/java/.../storage/LocalFilesystemStorageNode.java` — implementation (refactor from NodeStorageService)

**4b. Placement Policy Contract**

Create `PlacementPolicy` interface that enforces failure domain constraints:

```java
public interface PlacementPolicy {
    PlacementPlan placeShardsFor(String objectId, int totalShards, List<StorageNodeHealth> candidates);
}

public record PlacementPlan(
    Map<Integer, Integer> shardToNodeAssignment,   // shardIndex → nodeId
    List<String> warnings                          // constraint violations, capacity warnings
) {}

public record FailureDomain(String type, String value) {}  // ("rack", "rack-1"), ("az", "us-east-1a")
```

Files to create:
- `src/main/java/.../placement/PlacementPolicy.java` — interface
- `src/main/java/.../placement/PlacementPlan.java` — record
- `src/main/java/.../placement/FailureDomain.java` — record
- `src/main/java/.../placement/TopologyAwarePlacementPolicy.java` — implementation (wraps ConsistentHashRing + enforces domain constraints)

**4c. Domain Model Records**

Core records shared across scrubber/repair/GC:

```java
public record Shard(ShardId shardId, String objectId, int shardIndex, int nodeId, Instant createdAt) {}
public record ShardLocation(String objectId, int shardIndex, int nodeId, long epoch) {}
public record ScrubResult(ShardId shardId, ScrubOutcome outcome, Instant scrubbedAt, String detail) {}
public record RepairTask(String taskId, String objectId, int missingShardIndex, RepairPriority priority, Instant createdAt, Instant nextRunAt) {}
public record RepairResult(String taskId, RepairOutcome outcome, int shardsRead, int shardsWritten, Instant completedAt, String detail) {}
public record RepairBudget(int maxConcurrentRepairs, int maxShardsReadPerRun, int maxShardsWrittenPerRun) {}
public record RepairLease(String taskId, String ownerId, long fencingToken, Instant acquiredAt, Instant expiresAt, boolean active) {}
public record GCResult(int orphansQuarantined, int orphansPurged, int abandonedUploadsAborted, Instant completedAt) {}

public enum ScrubOutcome { CLEAN, CHECKSUM_MISMATCH, MISSING, READ_ERROR }
public enum RepairOutcome { REPAIRED, INSUFFICIENT_SHARDS, LEASE_CONFLICT, BUDGET_EXHAUSTED, FAILED }
public enum RepairPriority { CRITICAL, HIGH, NORMAL }  // based on remaining shard count
```

Files to create:
- `src/main/java/.../model/Shard.java`
- `src/main/java/.../model/ShardLocation.java`
- `src/main/java/.../model/ScrubResult.java`
- `src/main/java/.../model/RepairTask.java`
- `src/main/java/.../model/RepairResult.java`
- `src/main/java/.../model/RepairBudget.java`
- `src/main/java/.../model/RepairLease.java`
- `src/main/java/.../model/GCResult.java`
- `src/main/java/.../model/ScrubOutcome.java`
- `src/main/java/.../model/RepairOutcome.java`
- `src/main/java/.../model/RepairPriority.java`

**Tests for Phase 4**:
- `LocalFilesystemStorageNodeTest.java` — write/read/delete/list/checksum/health
- `TopologyAwarePlacementPolicyTest.java` — placement respects failure domains, rejects unsafe placements

---

### Phase 5: Scrubber (Background Integrity Verification)
**Goal**: Continuously verify every shard on every node; detect corruption before it accumulates past erasure tolerance.
**Depends on**: Phase 4 (StorageNode contract, ScrubResult model)

**5a. Scrubber Contract**

```java
public interface ShardScrubber {
    ScrubSummary scrubNode(int nodeId, ScrubBudget budget);
}

public record ScrubBudget(int maxShardsPerRun, Duration maxDuration) {}
public record ScrubSummary(int shardsScanned, int clean, int corrupt, int missing, int errors, ShardId resumeCursor) {}
```

**5b. Implementation: `BackgroundScrubber`**

Algorithm (from research — MinIO scanner + Ceph scheduling model):
```
1. Load persistent scan cursor for this node (last scanned shardId, or start from beginning)
2. List shards from StorageNode.listShards() starting after cursor
3. For each shard (up to budget.maxShardsPerRun):
   a. Read shard bytes via StorageNode.readShard()
   b. Compute MD5 checksum of bytes
   c. Look up expected checksum from ObjectMetadata (via shardId → objectId mapping)
   d. Compare:
      - Match → ScrubOutcome.CLEAN, advance cursor
      - Mismatch → ScrubOutcome.CHECKSUM_MISMATCH, create RepairTask, advance cursor
      - Shard missing on disk → ScrubOutcome.MISSING, create RepairTask
      - Read error → ScrubOutcome.READ_ERROR, log and skip
   e. Throttle: sleep between shards to avoid saturating disk I/O
4. Persist scan cursor for next run
5. Return ScrubSummary with counts and resume cursor
```

Files to create:
- `src/main/java/.../scrubber/ShardScrubber.java` — interface
- `src/main/java/.../scrubber/ScrubBudget.java` — record
- `src/main/java/.../scrubber/ScrubSummary.java` — record
- `src/main/java/.../scrubber/BackgroundScrubber.java` — implementation
- `src/main/java/.../scrubber/ScrubCursorStore.java` — interface (persist cursor position)
- `src/main/java/.../scrubber/FileScrubCursorStore.java` — file-backed implementation

**Tests for Phase 5**:
- `BackgroundScrubberTest.java` — clean shards pass, corrupt shards detected, missing shards detected, budget respected, cursor advances

---

### Phase 6: Repair Orchestrator
**Goal**: Reconstruct missing/corrupt shards from survivors and place replacements.
**Depends on**: Phase 4 (domain model, placement), Phase 5 (scrubber produces RepairTasks)

**6a. Repair Contracts**

```java
public interface RepairOrchestrator {
    RepairResult repair(RepairTask task, RepairBudget budget);
}

public interface RepairLeaseStore {
    Optional<RepairLease> tryAcquire(String taskId, String ownerId, Duration leaseDuration);
    void release(String taskId, long fencingToken);
    List<RepairLease> loadActiveLeases();
}

public interface RepairTaskQueue {
    void enqueue(RepairTask task);
    List<RepairTask> dueTasks(int maxTasks);
    void markCompleted(String taskId, RepairResult result);
    void reschedule(String taskId, Instant nextRunAt);
}
```

**6b. Implementation: `ErasureRepairOrchestrator`**

Algorithm (from research — Ceph recovery model):
```
1. Acquire fenced repair lease for this task (prevent concurrent repair of same object)
   - If lease held by another worker → RepairOutcome.LEASE_CONFLICT, skip
2. Read object metadata: get shard_locations, identify which shards are present/missing
3. Fetch k surviving shards from StorageNodes
   - If fewer than k available → RepairOutcome.INSUFFICIENT_SHARDS
4. Erasure-decode to reconstruct missing shard (reuse ErasureCodingService.decode())
5. Select replacement node via PlacementPolicy (must satisfy failure domain constraints)
6. Write replacement shard to selected node
7. CAS-update metadata: update shard_locations JSON only if epoch matches (prevent stale overwrite)
   - If CAS fails → retry or abandon (another repair may have already fixed it)
8. Release repair lease
9. Return RepairResult
```

**6c. Repair Scheduling**

```java
public class RepairScheduler {
    // Runs on a configurable interval (e.g., every 30 seconds)
    // Pulls due tasks from RepairTaskQueue
    // Respects RepairBudget (max concurrent, max shards read/written per run)
    // Priority: CRITICAL (1-2 shards remaining) > HIGH (3-4) > NORMAL (5)
    // Reschedules incomplete tasks with backoff
}
```

Files to create:
- `src/main/java/.../repair/RepairOrchestrator.java` — interface
- `src/main/java/.../repair/ErasureRepairOrchestrator.java` — implementation
- `src/main/java/.../repair/RepairLeaseStore.java` — interface
- `src/main/java/.../repair/InMemoryRepairLeaseStore.java` — implementation
- `src/main/java/.../repair/RepairTaskQueue.java` — interface
- `src/main/java/.../repair/InMemoryRepairTaskQueue.java` — implementation
- `src/main/java/.../repair/RepairScheduler.java` — scheduler

**Tests for Phase 6**:
- `ErasureRepairOrchestratorTest.java` — repair with 1 missing shard, repair with 2 missing, insufficient shards fails, CAS conflict handled, lease prevents concurrent repair
- `InMemoryRepairLeaseStoreTest.java` — acquire, release, fencing token monotonic, expired lease reacquirable
- `RepairSchedulerTest.java` — due tasks executed, budget respected, priority ordering, reschedule on incomplete

---

### Phase 7: GC Service
**Goal**: Clean orphaned shards and abandoned multipart uploads.
**Depends on**: Phase 4 (StorageNode, domain model)

**7a. GC Contracts**

```java
public interface GarbageCollector {
    GCResult collect(GCPolicy policy);
}

public record GCPolicy(Duration orphanGracePeriod, Duration abandonedUploadMaxAge) {}
```

**7b. Implementation: `OrphanAndLifecycleGC`**

Algorithm (from research — MinIO quarantine model):
```
Orphan shard cleanup:
1. List all shards across all StorageNodes (via StorageNode.listShards())
2. For each shard: check if it's referenced by any ObjectMetadata row
3. If not referenced and older than gracePeriod → quarantine (mark, don't delete immediately)
4. If quarantined and past quarantine expiry → delete permanently
5. Count orphansQuarantined, orphansPurged

Abandoned multipart upload cleanup:
1. Query multipart_upload table for INITIATED uploads older than abandonedUploadMaxAge
2. For each: delete all part shards, mark upload ABORTED
3. Count abandonedUploadsAborted
```

Files to create:
- `src/main/java/.../gc/GarbageCollector.java` — interface
- `src/main/java/.../gc/GCPolicy.java` — record
- `src/main/java/.../gc/OrphanAndLifecycleGC.java` — implementation

**Tests for Phase 7**:
- `OrphanAndLifecycleGCTest.java` — orphan detected, grace period respected, abandoned upload aborted, referenced shards not deleted

---

### Phase 8: Refactor Gateway Controller
**Goal**: Decompose the monolithic `StorageGatewayController` into service classes with defined responsibilities.
**Depends on**: Phases 4–7 (new service contracts exist)

Move logic out of the controller:
- `PutObjectService.java` — checksum → encode → place → write shards → persist metadata
- `GetObjectService.java` — metadata lookup → fetch shards → decode → checksum verify
- `DeleteObjectService.java` — metadata lookup → delete shards → delete metadata

The controller becomes a thin HTTP adapter that delegates to services. This matches the KV store's pattern: `CoordinatorService` owns the logic, HTTP handlers are thin.

Files to create:
- `src/main/java/.../service/PutObjectService.java`
- `src/main/java/.../service/GetObjectService.java`
- `src/main/java/.../service/DeleteObjectService.java`
- Update `StorageGatewayController.java` to delegate

---

### Phase 9: Update Blog and Docs
**Goal**: Blog Parts 8–9 reference real code instead of "architecture only."
**Depends on**: Phases 4–8 complete

- Update `docs/code-companion.md` — scrubber, repair, GC point to real source files
- Update `docs/implementation-plan.md` — Phases 4–7 marked as Done with code maps
- Update blog Part 8 (Scrubber + Repair) to show actual code
- Update blog Part 9 (Failure Modes) to reference repair flow
- Remove "architecture only" markers from gaps table for implemented components
- Add tests that demonstrate the repair flow end-to-end

---

## File Inventory (all files to create across all phases)

### Phase 4 (Foundation) — ~15 files
- `storage/StorageNode.java` (interface)
- `storage/ShardId.java` (record)
- `storage/StorageNodeHealth.java` (record)
- `storage/LocalFilesystemStorageNode.java` (implementation)
- `placement/PlacementPolicy.java` (interface)
- `placement/PlacementPlan.java` (record)
- `placement/FailureDomain.java` (record)
- `placement/TopologyAwarePlacementPolicy.java` (implementation)
- `model/Shard.java`, `model/ShardLocation.java`, `model/ScrubResult.java`
- `model/RepairTask.java`, `model/RepairResult.java`, `model/RepairBudget.java`
- `model/RepairLease.java`, `model/GCResult.java`
- `model/ScrubOutcome.java`, `model/RepairOutcome.java`, `model/RepairPriority.java` (enums)

### Phase 5 (Scrubber) — ~6 files
- `scrubber/ShardScrubber.java` (interface)
- `scrubber/ScrubBudget.java`, `scrubber/ScrubSummary.java` (records)
- `scrubber/BackgroundScrubber.java` (implementation)
- `scrubber/ScrubCursorStore.java` (interface)
- `scrubber/FileScrubCursorStore.java` (implementation)

### Phase 6 (Repair) — ~7 files
- `repair/RepairOrchestrator.java` (interface)
- `repair/ErasureRepairOrchestrator.java` (implementation)
- `repair/RepairLeaseStore.java` (interface)
- `repair/InMemoryRepairLeaseStore.java` (implementation)
- `repair/RepairTaskQueue.java` (interface)
- `repair/InMemoryRepairTaskQueue.java` (implementation)
- `repair/RepairScheduler.java` (scheduler)

### Phase 7 (GC) — ~3 files
- `gc/GarbageCollector.java` (interface)
- `gc/GCPolicy.java` (record)
- `gc/OrphanAndLifecycleGC.java` (implementation)

### Phase 8 (Refactor) — ~3 files + 1 update
- `service/PutObjectService.java`
- `service/GetObjectService.java`
- `service/DeleteObjectService.java`
- Update `StorageGatewayController.java`

### Tests — ~8 files
- `storage/LocalFilesystemStorageNodeTest.java`
- `placement/TopologyAwarePlacementPolicyTest.java`
- `scrubber/BackgroundScrubberTest.java`
- `repair/ErasureRepairOrchestratorTest.java`
- `repair/InMemoryRepairLeaseStoreTest.java`
- `repair/RepairSchedulerTest.java`
- `gc/OrphanAndLifecycleGCTest.java`
- (existing) `erasure/ErasureCodingServiceTest.java`
- (existing) `routing/ConsistentHashRingTest.java`

**Total new files**: ~34 source + ~7 test = ~41 files
**Estimated new LOC**: ~2,500–3,000 source + ~800–1,000 test

## Build Order (sequential, each phase depends on prior)

```
Phase 4 (Foundation)  →  Phase 5 (Scrubber)  →  Phase 6 (Repair)  →  Phase 7 (GC)  →  Phase 8 (Refactor)  →  Phase 9 (Blog/Docs)
```

## Session Continuity

If the session resets, the next session should:
1. Read this file: `/Users/hemantkgupta/code-all/distributed-object-storage/docs/deep-research-build-plan.md`
2. Read the research report: check `raw/` or attachments for the deep research report
3. Read existing source: `/Users/hemantkgupta/code-all/distributed-object-storage/src/main/java/com/systemdesign/objectstorage/`
4. Check which phases are done by looking at the source tree
5. Continue from the next incomplete phase

## What NOT to Build (from research)

- LRC or CLAY erasure codes — complexity not justified for MVP
- Geo-distributed shard striping — object-level replication is the right DR primitive
- S3 storage class internals — not publicly documented
- MTTDL simulator — durability modelling is an appendix, not code
- gRPC transport — JDK HTTP or Spring Boot is sufficient for companion project
