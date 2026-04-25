# Distributed Object Storage — Parity Plan

Bring the Object Storage project to the same quality bar as the distributed-key-value-store project.

## Gold Standard (what KV store has)

| Artifact | KV Store | Object Storage (current) | Gap |
|---|---|---|---|
| `docs/adr/0001-*.md` | ✅ Why Dynamo over Raft/FoundationDB | ❌ | Missing |
| `docs/implementation-plan.md` | ✅ 7-phase plan with per-phase code map | ❌ | Missing entirely |
| `docs/code-companion.md` | ✅ Maps every blog section → source file | ❌ | Missing |
| `docs/research-checkpoint.md` | ✅ Design rationale, recommended defaults | ❌ | Missing |
| Test files | 30 test files across 6 modules | 0 test files | Missing entirely |
| Module structure | 13 Gradle modules with clean contracts | 1 monolith module | Needs splitting |
| Blog references actual code | ✅ Every part shows real code from repo | ✅ Blog references code well | Already good |
| Documented gaps | ✅ In impl plan + code companion | ❌ | Missing |
| Background services (scrubber, repair) | N/A (different domain) | ❌ Blog describes them, code doesn't have them | Missing code |

## Current Source File Inventory

```
src/main/java/com/systemdesign/objectstorage/
├── DistributedObjectStorageApplication.java    (Spring Boot main)
├── controller/
│   └── StorageGatewayController.java           (PUT/GET/DELETE — the core gateway)
├── erasure/
│   └── ErasureCodingService.java               (Reed-Solomon GF(2^8) — self-contained)
├── model/
│   └── ObjectMetadata.java                     (JPA entity — bucket, key, shard locations)
├── multipart/
│   ├── MultipartUpload.java                    (JPA entity — upload lifecycle)
│   ├── MultipartUploadController.java          (4-step S3-compatible multipart API)
│   ├── MultipartUploadRepository.java          (JPA repo)
│   └── UploadPart.java                         (JPA entity — per-part shard map)
│   └── UploadPartRepository.java               (JPA repo)
├── repository/
│   └── MetadataRepository.java                 (JPA repo — findByBucketAndObjectKey)
├── routing/
│   └── ConsistentHashRing.java                 (150 vnodes, SHA-256, TreeMap ring)
└── service/
    └── NodeStorageService.java                 (local FS shard read/write/delete)
```

12 source files, 1,115 LOC, 0 tests, 0 docs.

## Tasks

### 1. Create `docs/adr/0001-erasure-coding-over-replication.md`

**What**: Architecture Decision Record explaining the core design choice.
**Content**:
- Decision: Reed-Solomon erasure coding (4,2) over 3-way replication
- Why self-contained GF(2^8) implementation over an external library (no dependency, full understanding, educational value)
- Why (4,2) over (8,4) or (14,4) — read amplification vs storage cost tradeoff
- Why placement stored at write time (not re-derived from ring on GET)
- Why per-part erasure coding in multipart (avoids 5 TB in-memory assembly)
- Why storage-first, metadata-second ordering (orphaned shards are harmless, missing shards are correctness failures)

**File**: `/Users/hemantkgupta/code-all/distributed-object-storage/docs/adr/0001-erasure-coding-over-replication.md`

### 2. Create `docs/implementation-plan.md`

**What**: Phased build plan with code map per phase (same format as KV store).
**Content**:

```
## Phase 1: Core Gateway + Erasure Coding — Done
- Defined object metadata model, erasure coding parameters, shard location storage.
- Implemented self-contained Reed-Solomon encoder/decoder over GF(2^8).
- Implemented PUT/GET/DELETE via StorageGatewayController.
- Added MD5 checksum computation on PUT, verification on GET.
- Added Flyway schema migration (V1__init.sql).

## Phase 1 Code Map
- controller/StorageGatewayController.java — PUT (5-step flow), GET (degraded read), DELETE
- erasure/ErasureCodingService.java — GF(2^8) tables, generator matrix, encode(), decode()
- model/ObjectMetadata.java — JPA entity with shardLocations JSON
- service/NodeStorageService.java — local filesystem shard I/O
- resources/db/migration/V1__init.sql — object_metadata table

## Phase 2: Consistent Hash Ring — Done
- Implemented SHA-256-based consistent hash ring with 150 virtual nodes.
- Ring assigns shards to nodes on PUT; placement stored in metadata.
- Added addNode/removeNode for topology changes.

## Phase 2 Code Map
- routing/ConsistentHashRing.java — buildRing(), getNode(), addNode(), removeNode()

## Phase 3: Multipart Upload — Done
- Implemented 4-step S3-compatible multipart API (initiate, upload part, complete, abort).
- Each part independently erasure-coded (avoids in-memory assembly).
- Added idempotent part retry (UNIQUE constraint, old shard cleanup).
- Added composite ETag computation (S3 convention).
- Added Flyway migration (V2__multipart_upload.sql).

## Phase 3 Code Map
- multipart/MultipartUploadController.java — 4-step protocol
- multipart/MultipartUpload.java — upload lifecycle entity
- multipart/UploadPart.java — per-part shard map entity
- resources/db/migration/V2__multipart_upload.sql — multipart schema

## Phase 4: Scrubber — Planned
- Add background per-node shard verification.
- Compute checksum of each shard, compare to stored checksum.
- Report corrupted shards for repair.

## Phase 5: Repair Orchestrator — Planned
- Detect missing/corrupted shards.
- Reconstruct from surviving shards via erasure decode.
- Write replacement to healthy node.
- Update shard_locations in metadata.

## Phase 6: GC Service — Planned
- Orphan shard cleanup (shards with no metadata reference).
- Abandoned multipart upload cleanup (INITIATED > 7 days).

## Phase 7: Module Split — Planned
- Split monolith into separate Gradle modules matching blog's service boundaries.
```

**File**: `/Users/hemantkgupta/code-all/distributed-object-storage/docs/implementation-plan.md`

### 3. Create `docs/code-companion.md`

**What**: Map every blog part to the source files that implement it.
**Content**:

```
| Blog Part | Code Location | Status |
|---|---|---|
| Part 2: System Overview | All modules | Architecture diagram only |
| Part 3: Write Path (PUT) | controller/StorageGatewayController.java (putObject) | Implemented |
| Part 4: Erasure Coding | erasure/ErasureCodingService.java | Implemented (self-contained GF(2^8)) |
| Part 5: Metadata + Placement | model/ObjectMetadata.java, routing/ConsistentHashRing.java, V1__init.sql | Implemented |
| Part 6: Read Path (GET) | controller/StorageGatewayController.java (getObject) | Implemented (with degraded read + checksum verify) |
| Part 7: Multipart Upload | multipart/MultipartUploadController.java, MultipartUpload.java, UploadPart.java | Implemented |
| Part 8: Scrubber + Repair | — | Not yet implemented (blog describes architecture) |
| Part 9: Failure Modes | — | Architecture only (no failure injection code) |
```

Include the KV store's "Sync Rule": *"When the blog claims a mechanism exists, this companion must point to the file or test that implements it. If the code only simulates a production behavior locally, say so here and in the blog."*

**File**: `/Users/hemantkgupta/code-all/distributed-object-storage/docs/code-companion.md`

### 4. Create `docs/research-checkpoint.md`

**What**: Design rationale and foundational concepts.
**Content**:
- Direction: S3-style distributed object storage with erasure coding
- Foundation: metadata/data plane split, consistent hashing, erasure coding, multipart upload
- Going Deeper: Reed-Solomon math, GF(2^8), generator matrix, encoding profiles, degraded reads
- At Scale: scrubbing, background repair, durability math (AFR × MTTR), failure domain placement
- Recommended defaults: (4,2) for general-purpose, (14,4) for cold archival, MD5 checksum on every GET
- Link to wiki sources: `concepts/erasure-coding`, `concepts/consistent-hashing`, `concepts/multipart-upload`, `concepts/object-storage`

**File**: `/Users/hemantkgupta/code-all/distributed-object-storage/docs/research-checkpoint.md`

### 5. Add tests — minimum viable test suite

**Priority order** (most architecturally important first):

| Test file | What it tests | Why critical |
|---|---|---|
| `ErasureCodingServiceTest.java` | Encode → decode roundtrip, decode with 1 missing shard, decode with 2 missing shards, decode with 3 missing shards (should fail), GF multiplication properties | Core algorithm correctness — the most important test in the project |
| `ConsistentHashRingTest.java` | Deterministic assignment, even distribution across 6 nodes, addNode only moves ~1/N shards, removeNode, empty ring exception | Placement correctness |
| `StorageGatewayControllerTest.java` | PUT → GET roundtrip, checksum verification on GET, GET with missing shard (degraded read), DELETE removes shards and metadata | End-to-end gateway correctness |
| `MultipartUploadControllerTest.java` | Initiate → upload 3 parts → complete → GET, ETag verification, idempotent part retry, abort cleans up shards | Multipart protocol correctness |
| `NodeStorageServiceTest.java` | Write shard → read shard → delete shard, shard exists check, missing shard returns correctly | Storage layer correctness |

Minimum: first 2 (erasure coding + consistent hashing). Ideal: all 5.

### 6. Add documented gaps to blog

**What**: Add a "Gaps vs Production" section or appendix to the blog at `CSE-Raw/raw-blog/distributed-object-storage.md`.
**Content** (similar to OJ's gaps table):

```
| Production Component | Local Substitute | Why |
|---|---|---|
| Distributed metadata (Cassandra/CockroachDB) | Single-node PostgreSQL | Multi-node not needed for local dev |
| Distributed storage nodes (separate hosts) | Local filesystem directories | Same read/write contract |
| Background scrubber daemon | Not implemented | Blog describes architecture |
| Repair orchestrator | Not implemented | Blog describes architecture |
| GC service | Not implemented | Blog describes architecture |
| Failure domain placement | Not enforced | Blog describes constraint |
| Cross-datacenter replication | Not implemented | Blog describes for resilience |
```

**File**: `CSE-Raw/raw-blog/distributed-object-storage.md` (update existing — add after Part 9 closing, before the final paragraph)

### 7. Verify blog ↔ code consistency

After steps 1–6, do a final pass:
- Every code excerpt in the blog must match the actual file in the repo (class name, method name, field names).
- Every file referenced in `code-companion.md` must exist.
- Every test referenced in the plan must exist and pass.
- The `implementation-plan.md` phase status must match reality.
- Blog Parts 8–9 (Scrubber, Repair, Failure Modes) must clearly state these are architecture-only, not yet implemented in code.

## File Inventory (what gets created/modified)

| File | Action | Repo |
|---|---|---|
| `docs/adr/0001-erasure-coding-over-replication.md` | **Create** | distributed-object-storage |
| `docs/implementation-plan.md` | **Create** | distributed-object-storage |
| `docs/code-companion.md` | **Create** | distributed-object-storage |
| `docs/research-checkpoint.md` | **Create** | distributed-object-storage |
| `docs/parity-plan.md` | Already created (this file) | distributed-object-storage |
| 2–5 test files (see table above) | **Create** | distributed-object-storage |
| `raw-blog/distributed-object-storage.md` | **Update** (add gaps section) | CSE-Raw |

## Order of Operations

1. Read all existing source files to understand current state (already done in previous session).
2. Create `docs/adr/`, `docs/implementation-plan.md`, `docs/code-companion.md`, `docs/research-checkpoint.md`.
3. Write tests (start with `ErasureCodingServiceTest`, `ConsistentHashRingTest`).
4. Add gaps section to blog.
5. Final consistency check: blog ↔ code ↔ docs.

## What NOT to Change

- Do not split into modules yet (Phase 7 — planned, not urgent for parity). The blog already describes the service boundaries; the code companion documents the monolith-to-module mapping.
- Do not implement Scrubber/Repair/GC yet (Phases 4–6). The blog describes the architecture; the code companion marks them as "not yet implemented." This is the same pattern the KV store uses for its planned phases.
- Do not change the blog's architecture content — it's already been restructured and is at quality.
- Do not change existing source code structure — it's clean and the blog references it accurately.

## Parallel Session Safety

This plan touches:
- `/Users/hemantkgupta/code-all/distributed-object-storage/` (code repo)
- `/Users/hemantkgupta/CSE-Raw/raw-blog/distributed-object-storage.md` (blog file)

The OJ plan touches:
- `/Users/hemantkgupta/code-all/online-judge-at-scale/` (different code repo)
- `/Users/hemantkgupta/CSE-Raw/raw-blog/online-judge-at-scale.md` (different blog file)

**Zero file overlap. Safe to run in parallel.**
