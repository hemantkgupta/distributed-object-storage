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

## Phase 5: Repair Orchestrator — Planned

- Detect missing/corrupted shards from scrubber reports, health monitor, or read-time checksum failures.
- Fetch k surviving shards from other storage nodes.
- Reconstruct missing shard via erasure decode.
- Write replacement shard to a healthy node (selected by consistent hash ring).
- Update `shard_locations` in metadata to reflect new placement.
- Priority queue: objects with fewer surviving shards are repaired first.

## Phase 6: GC Service — Planned

- Orphan shard cleanup: scan storage nodes for shards with no corresponding metadata reference. Delete them.
- Abandoned multipart upload cleanup: scan for `INITIATED` uploads older than configurable threshold (default 7 days). Abort them — delete parts, delete shards.

## Phase 7: Module Split — Planned

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
| Background scrubber daemon | Not implemented | Blog describes architecture; Phase 4 |
| Repair orchestrator | Not implemented | Blog describes architecture; Phase 5 |
| GC service | Not implemented | Blog describes architecture; Phase 6 |
| Failure domain placement (rack/power awareness) | Not enforced | Blog describes constraint; ring walk skips but doesn't check racks |
| Cross-datacenter replication | Not implemented | Blog describes for extreme resilience |
| SIMD-optimized erasure coding | Pure-Java GF(2^8) table lookup | Sufficient for project throughput; see ADR 0001 |
