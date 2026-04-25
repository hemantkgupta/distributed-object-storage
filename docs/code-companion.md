# Code Companion

This file maps the Complete Engineering Guide blog sections to code locations.

## Sync Rule

When the blog claims a mechanism exists, this companion must point to the file or test that implements it. If the code only simulates a production behavior locally, say so here and in the blog.

## Blog Part → Code Mapping

| Blog Part | Code Location | Status |
|---|---|---|
| Part 1: Problem + Numbers | — | Architecture only — no corresponding code |
| Part 2: System Overview | All source files | Architecture diagram; component inventory matches source tree |
| Part 3: Write Path (PUT) | `service/PutObjectService.java`, `controller/StorageGatewayController.java` (thin adapter) | Implemented — 5-step flow: checksum → encode → hash ring → write shards → persist metadata |
| Part 4: Erasure Coding | `erasure/ErasureCodingService.java` | Implemented — self-contained GF(2^8), generator matrix, encode(), decode() with Gaussian elimination |
| Part 5: Metadata Service | `model/ObjectMetadata.java`, `repository/MetadataRepository.java`, `resources/db/migration/V1__init.sql` | Implemented — JPA entity with shardLocations JSON, Flyway migration |
| Part 5: Placement Service | `routing/ConsistentHashRing.java`, `placement/PlacementPolicy.java`, `placement/TopologyAwarePlacementPolicy.java`, `placement/FailureDomain.java`, `placement/PlacementPlan.java` | Implemented — 150 vnodes, SHA-256, failure domain enforcement |
| Part 6: Read Path (GET) | `service/GetObjectService.java`, `controller/StorageGatewayController.java` (thin adapter) | Implemented — metadata lookup → fetch all shards → present[] tracking → decode → checksum verify → 200 or 409 |
| Part 7: Multipart Upload | `multipart/MultipartUploadController.java`, `MultipartUpload.java`, `UploadPart.java`, `UploadPartRepository.java`, `MultipartUploadRepository.java` | Implemented — 4-step S3 protocol, per-part erasure coding, idempotent retry, composite ETag, abort |
| Part 7: GC Service | `gc/GarbageCollector.java`, `gc/OrphanAndLifecycleGC.java`, `gc/GCPolicy.java` | Implemented — orphan quarantine-before-purge, abandoned multipart auto-abort |
| Part 8: Scrubber | `scrubber/ShardScrubber.java`, `scrubber/BackgroundScrubber.java`, `scrubber/ScrubBudget.java`, `scrubber/ScrubSummary.java` | Implemented — persistent cursor, budgeted scanning, checksum verification, repair task enqueuing |
| Part 8: Repair Orchestrator | `repair/RepairOrchestrator.java`, `repair/ErasureRepairOrchestrator.java` | Implemented — fenced lease, surviving shard fetch, erasure decode, replacement placement, metadata update |
| Part 8: Repair Leases | `repair/RepairLeaseStore.java`, `repair/InMemoryRepairLeaseStore.java` | Implemented — fencing tokens, acquire/release, expiry |
| Part 8: Repair Queue | `repair/RepairTaskQueue.java`, `repair/InMemoryRepairTaskQueue.java` | Implemented — priority ordering, due tasks, reschedule |
| Part 8: Durability Math | — | Architecture only — MTTR × AFR probability model, no corresponding code |
| Part 9: Failure Modes | — | Architecture only — no failure injection code |

## Storage Abstraction

| Contract | Implementation | Test |
|---|---|---|
| `storage/StorageNode.java` | `storage/LocalFilesystemStorageNode.java` | — |
| `placement/PlacementPolicy.java` | `placement/TopologyAwarePlacementPolicy.java` | — |
| `scrubber/ShardScrubber.java` | `scrubber/BackgroundScrubber.java` | — |
| `repair/RepairOrchestrator.java` | `repair/ErasureRepairOrchestrator.java` | — |
| `repair/RepairLeaseStore.java` | `repair/InMemoryRepairLeaseStore.java` | — |
| `repair/RepairTaskQueue.java` | `repair/InMemoryRepairTaskQueue.java` | — |
| `gc/GarbageCollector.java` | `gc/OrphanAndLifecycleGC.java` | — |

## Domain Model

| Record/Enum | Package | Purpose |
|---|---|---|
| `ShardId` | storage | Node ID + physical shard ID |
| `StorageNodeHealth` | storage | Capacity, usage, shard count, healthy flag |
| `FailureDomain` | placement | Rack/AZ/power constraint label |
| `PlacementPlan` | placement | Shard → node assignments + warnings |
| `ScrubResult` | model | Per-shard scrub outcome |
| `ScrubOutcome` | model | CLEAN, CHECKSUM_MISMATCH, MISSING, READ_ERROR |
| `ScrubBudget` | scrubber | Max shards per run + max duration |
| `ScrubSummary` | scrubber | Counts + resume cursor |
| `RepairTask` | model | Pending repair: object, shard index, priority, schedule |
| `RepairResult` | model | Repair outcome: shards read/written, detail |
| `RepairBudget` | model | Max concurrent, max reads, max writes |
| `RepairLease` | model | Task lock with fencing token and expiry |
| `RepairOutcome` | model | REPAIRED, INSUFFICIENT_SHARDS, LEASE_CONFLICT, BUDGET_EXHAUSTED, FAILED |
| `RepairPriority` | model | CRITICAL (4/6), HIGH (5/6), NORMAL (6/6) |
| `GCResult` | model | Orphans quarantined/purged, uploads aborted |
| `GCPolicy` | gc | Grace period + abandoned upload max age |

## Gateway Decomposition

The gateway controller was refactored into thin adapter + service layer:

| Component | File | Responsibility |
|---|---|---|
| HTTP adapter | `controller/StorageGatewayController.java` | Translate HTTP → service calls → HTTP responses |
| PUT service | `service/PutObjectService.java` | Checksum → encode → place → write → persist |
| GET service | `service/GetObjectService.java` | Metadata → fetch → decode → verify |
| DELETE service | `service/DeleteObjectService.java` | Metadata → delete shards → delete metadata |

## Key Implementation Details

### Erasure Coding (blog Part 4 → `ErasureCodingService.java`)

- GF(2^8) primitive polynomial: `0x11D` (x^8 + x^4 + x^3 + x^2 + 1)
- EXP/LOG tables: 512/256 entries, precomputed in static initializer
- Multiplication: `gfMul(a, b) = EXP[LOG[a] + LOG[b]]` — O(1) per byte
- Generator matrix: k=4 identity rows + m=2 Vandermonde rows
- Decode: select k available → extract submatrix → invert via Gaussian elimination → multiply
- Blog code excerpts match the actual source file exactly

### Scrubber (blog Part 8 → `BackgroundScrubber.java`)

- Persistent cursor per node (resumes from last position)
- Sorted shard inventory walk with budget enforcement
- Checksum comparison against metadata store
- Creates RepairTask on mismatch/missing — does NOT delete or repair inline
- Orphan shards (no metadata reference) skipped — that's GC's responsibility

### Repair Orchestrator (blog Part 8 → `ErasureRepairOrchestrator.java`)

- Acquires fenced repair lease before starting
- Reads metadata for shard locations
- Fetches all shards, tracks present[] array
- Erasure-decodes to reconstruct original, re-encodes to get all shards
- Writes missing shards to healthy replacement nodes
- Updates metadata shard_locations JSON
- Releases lease in finally block (always releases, even on failure)

### GC Service (blog Part 7 → `OrphanAndLifecycleGC.java`)

- Quarantine-before-purge: orphans tracked by first-seen time, only deleted after grace period
- Abandoned uploads: INITIATED status older than max age → auto-abort
- Full metadata scan to build referenced shard set — appropriate for periodic GC, not hot path
