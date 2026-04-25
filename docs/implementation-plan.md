# Implementation Plan

## Phase 1: Core Gateway + Erasure Coding — Done

- Defined object metadata model with bucket, key, checksum, erasure coding parameters, and shard location JSON.
- Implemented self-contained Reed-Solomon encoder/decoder over GF(2^8) with primitive polynomial 0x11D.
- Implemented PUT/GET/DELETE via StorageGatewayController with storage-first, metadata-second ordering.
- Added MD5 checksum computation on PUT, verification on GET with HTTP 409 Conflict on mismatch.
- Added Flyway schema migration (V1__init.sql) for `object_metadata` table.
- Added Spring Boot application with PostgreSQL datasource and JPA.

### Phase 1 Code Map

- `src/main/java/.../controller/StorageGatewayController.java` — PUT (checksum → encode → hash ring → write shards → persist metadata → 200), GET (metadata lookup → fetch all shards → track present[] → decode → checksum verify → 200 or 409), DELETE (metadata lookup → delete shards → delete metadata).
- `src/main/java/.../erasure/ErasureCodingService.java` — GF(2^8) exp/log tables from primitive polynomial 0x11D, gfMul/gfDiv/gfPow via table lookup, Vandermonde generator matrix, `encode()` splits payload into k=4 data + m=2 parity shards, `decode()` selects k available shards → extracts submatrix → inverts in GF(2^8) via Gaussian elimination → multiplies inverse × shard bytes.
- `src/main/java/.../model/ObjectMetadata.java` — JPA entity: id (UUID), bucket, objectKey, totalSizeBytes, checksum (Base64 MD5), dataShards, parityShards, baseShardId, shardLocations (JSON TEXT), createdAt.
- `src/main/java/.../repository/MetadataRepository.java` — `findByBucketAndObjectKey()`, `deleteByBucketAndObjectKey()`.
- `src/main/java/.../service/NodeStorageService.java` — local filesystem shard I/O: writeShard, readShard, deleteShard, shardExists. Simulates distinct nodes as separate directories (`data/storage-nodes/node-{id}/`).
- `src/main/resources/db/migration/V1__init.sql` — `object_metadata` table with UNIQUE(bucket, object_key) constraint.
- `src/main/resources/application.yml` — PostgreSQL datasource, Flyway config, storage node IDs [1,2,3,4,5,6].

## Phase 2: Consistent Hash Ring — Done

- Implemented SHA-256-based consistent hash ring with 150 virtual nodes per physical node.
- Ring assigns shards to nodes on PUT via clockwise walk; placement stored in metadata `shard_locations` JSON at write time.
- Added addNode/removeNode for topology changes. Existing objects unaffected — their stored placement remains authoritative.

### Phase 2 Code Map

- `src/main/java/.../routing/ConsistentHashRing.java` — `buildRing()` creates 150 vnodes per physical node via SHA-256(node-{id}-vnode-{i}) → first 8 bytes as long. `getNode(shardId)` hashes shardId → TreeMap tailMap clockwise walk → returns physical nodeId. `addNode()`/`removeNode()` modify the ring for new writes only.

## Phase 3: Multipart Upload — Done

- Implemented 4-step S3-compatible multipart API: initiate, upload part, complete, abort.
- Each part independently erasure-coded at upload time (avoids in-memory assembly of entire object on complete).
- Added idempotent part retry: UNIQUE(upload_id, part_number) constraint, old shard cleanup before overwrite.
- Added composite ETag computation following S3 convention: MD5 of concatenated binary MD5 digests of all parts.
- Added Flyway migration (V2__multipart_upload.sql) for `multipart_upload` and `upload_part` tables.

### Phase 3 Code Map

- `src/main/java/.../multipart/MultipartUploadController.java` — `initiateMultipartUpload()` creates INITIATED row, returns uploadId. `uploadPart()` erasure-codes the part, writes shards, stores per-part shard map, returns ETag. `completeMultipartUpload()` verifies manifest ETags against stored parts, assembles composite `object_metadata` row, computes composite ETag, marks COMPLETED. `abortMultipartUpload()` deletes all part shards, marks ABORTED.
- `src/main/java/.../multipart/MultipartUpload.java` — JPA entity: id (UUID), bucket, objectKey, status (INITIATED/COMPLETED/ABORTED), createdAt, updatedAt.
- `src/main/java/.../multipart/UploadPart.java` — JPA entity: id (UUID), uploadId (FK), partNumber, sizeBytes, etag, baseShardId, shardLocations (per-part JSON).
- `src/main/java/.../multipart/MultipartUploadRepository.java` — `findByIdAndStatus()`.
- `src/main/java/.../multipart/UploadPartRepository.java` — `findByUploadIdAndPartNumber()`, `findByUploadIdOrderByPartNumberAsc()`.
- `src/main/resources/db/migration/V2__multipart_upload.sql` — `multipart_upload` table with `upload_status` enum, `upload_part` table with FK cascade and UNIQUE(upload_id, part_number).

## Phase 4: Scrubber — Planned

- Add background per-node shard verification daemon.
- Read each shard from local disk, compute checksum, compare to expected checksum from metadata.
- On mismatch: mark shard as corrupted, delete from disk, report to repair orchestrator.
- Throttle I/O to avoid competing with production reads and writes.
- Target: full scan cycle verifies every shard at least once per week.

## Phase 4: Foundation Contracts and Domain Model — Done

- Defined `StorageNode` interface with listShards(), shardChecksum(), health().
- Implemented `LocalFilesystemStorageNode` backed by local filesystem directories.
- Defined `PlacementPolicy` interface with failure domain enforcement.
- Implemented `TopologyAwarePlacementPolicy` wrapping ConsistentHashRing with rack/AZ constraint checking.
- Added domain model records: ShardId, StorageNodeHealth, FailureDomain, PlacementPlan.
- Added repair domain model: RepairTask, RepairResult, RepairBudget, RepairLease, RepairOutcome, RepairPriority.
- Added scrub domain model: ScrubResult, ScrubOutcome.
- Added GC domain model: GCResult.

### Phase 4 Code Map

- `storage/StorageNode.java` — interface: read/write/delete/list/checksum/health
- `storage/LocalFilesystemStorageNode.java` — filesystem implementation with MD5 checksumming
- `storage/ShardId.java` — nodeId + physicalShardId record
- `storage/StorageNodeHealth.java` — capacity, usage, shard count, health flag
- `placement/PlacementPolicy.java` — interface: place shards under failure domain constraints
- `placement/TopologyAwarePlacementPolicy.java` — ring + domain enforcement + capacity warnings
- `placement/FailureDomain.java` — (type, value) label for rack/AZ/power
- `placement/PlacementPlan.java` — shard → node assignments + warnings
- `model/ScrubResult.java`, `model/ScrubOutcome.java` — per-shard scrub outcome
- `model/RepairTask.java`, `model/RepairResult.java`, `model/RepairBudget.java` — repair domain
- `model/RepairLease.java`, `model/RepairOutcome.java`, `model/RepairPriority.java` — lease + priority
- `model/GCResult.java` — GC run outcome

## Phase 5: Scrubber — Done

- Defined `ShardScrubber` interface with budgeted `scrubNode()`.
- Implemented `BackgroundScrubber` with persistent per-node scan cursor.
- Cursor-based inventory walk: sorted shard list, resume from last position.
- Checksum verification: compute MD5 of shard bytes, compare to expected from metadata.
- Mismatch/missing → enqueue RepairTask via RepairTaskQueue. Does NOT delete or repair inline.
- Budget enforcement: stops after maxShardsPerRun or maxDuration.

### Phase 5 Code Map

- `scrubber/ShardScrubber.java` — interface
- `scrubber/BackgroundScrubber.java` — persistent cursor, checksum comparison, repair task enqueuing
- `scrubber/ScrubBudget.java` — max shards per run + max duration
- `scrubber/ScrubSummary.java` — counts (clean/corrupt/missing/errors) + resume cursor

## Phase 6: Repair Orchestrator — Done

- Defined `RepairOrchestrator` interface.
- Implemented `ErasureRepairOrchestrator` with fenced lease coordination.
- Algorithm: acquire lease → read metadata → fetch surviving shards → erasure decode → re-encode → write missing shards to healthy nodes → update metadata → release lease.
- Defined `RepairLeaseStore` interface with fencing tokens.
- Implemented `InMemoryRepairLeaseStore` with ConcurrentHashMap and monotonic AtomicLong tokens.
- Defined `RepairTaskQueue` interface with priority ordering.
- Implemented `InMemoryRepairTaskQueue` with ConcurrentHashMap and priority+schedule sorting.

### Phase 6 Code Map

- `repair/RepairOrchestrator.java` — interface: repair(task, budget) → RepairResult
- `repair/ErasureRepairOrchestrator.java` — full repair flow with lease, decode, place, metadata CAS
- `repair/RepairLeaseStore.java` — interface: tryAcquire, release, loadActiveLeases
- `repair/InMemoryRepairLeaseStore.java` — ConcurrentHashMap, monotonic fencing tokens
- `repair/RepairTaskQueue.java` — interface: enqueue, dueTasks, markCompleted, reschedule
- `repair/InMemoryRepairTaskQueue.java` — priority ordering, due filtering

## Phase 7: GC Service — Done

- Defined `GarbageCollector` interface.
- Implemented `OrphanAndLifecycleGC` with quarantine-before-purge semantics.
- Orphan detection: scan all storage nodes, compare shard inventory against metadata references.
- Grace period enforcement: orphans tracked by first-seen time, purged only after grace period.
- Abandoned multipart cleanup: INITIATED uploads older than configurable max age → auto-abort.

### Phase 7 Code Map

- `gc/GarbageCollector.java` — interface: collect(policy) → GCResult
- `gc/OrphanAndLifecycleGC.java` — orphan quarantine + abandoned upload abort
- `gc/GCPolicy.java` — orphanGracePeriod + abandonedUploadMaxAge

## Phase 8: Gateway Controller Refactor — Done

- Extracted business logic from `StorageGatewayController` into three service classes.
- Controller is now a thin HTTP adapter with no business logic.
- Matches KV store's pattern: `CoordinatorService` owns logic, HTTP handlers are thin.

### Phase 8 Code Map

- `service/PutObjectService.java` — checksum → encode → place → write → persist
- `service/GetObjectService.java` — metadata → fetch → decode → verify (returns GetResult record)
- `service/DeleteObjectService.java` — metadata → delete shards → delete metadata
- `controller/StorageGatewayController.java` — thin adapter delegating to services

## Phase 9: Module Split — Planned

- Split monolith into Gradle modules matching the blog's service boundaries:
  - `storage-gateway` — StorageGatewayController, MultipartUploadController
  - `erasure-coding` — ErasureCodingService
  - `metadata-service` — ObjectMetadata, repositories, Flyway migrations
  - `placement-service` — ConsistentHashRing
  - `storage-node` — NodeStorageService
  - `repair` — Scrubber, RepairOrchestrator (when implemented)
  - `common` — shared types

## Gaps vs Production

| Production Component | Local Substitute | Why |
|---|---|---|
| Distributed metadata (Cassandra/CockroachDB) | Single-node PostgreSQL | Multi-node not needed for local dev |
| Distributed storage nodes (separate hosts) | Local filesystem directories per node | Same read/write/delete contract |
| Background scrubber daemon | `BackgroundScrubber.java` — persistent cursor, budgeted, checksum verification | ✅ Implemented (Phase 5) |
| Repair orchestrator | `ErasureRepairOrchestrator.java` — fenced leases, decode, replace, metadata update | ✅ Implemented (Phase 6) |
| GC service | `OrphanAndLifecycleGC.java` — quarantine-before-purge, abandoned upload abort | ✅ Implemented (Phase 7) |
| Failure domain placement (rack/power awareness) | Not enforced | Blog describes constraint; ring walk skips but doesn't check racks |
| Cross-datacenter replication | Not implemented | Blog describes for extreme resilience |
| SIMD-optimized erasure coding | Pure-Java GF(2^8) table lookup | Sufficient for project throughput; see ADR 0001 |
