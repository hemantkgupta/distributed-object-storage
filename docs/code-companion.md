# Code Companion

This file maps the Complete Engineering Guide blog sections to code locations.

## Sync Rule

When the blog claims a mechanism exists, this companion must point to the file or test that implements it. If the code only simulates a production behavior locally, say so here and in the blog.

## Blog Part → Code Mapping

| Blog Part | Code Location | Status |
|---|---|---|
| Part 1: Problem + Numbers | — | Architecture only — no corresponding code |
| Part 2: System Overview | All source files | Architecture diagram; component inventory matches source tree |
| Part 3: Write Path (PUT) | `controller/StorageGatewayController.java` (`putObject`) | Implemented — 5-step flow: checksum → encode → hash ring → write shards → persist metadata |
| Part 4: Erasure Coding | `erasure/ErasureCodingService.java` | Implemented — self-contained GF(2^8), generator matrix, encode(), decode() with Gaussian elimination |
| Part 5: Metadata Service | `model/ObjectMetadata.java`, `repository/MetadataRepository.java`, `resources/db/migration/V1__init.sql` | Implemented — JPA entity with shardLocations JSON, Flyway migration |
| Part 5: Placement Service | `routing/ConsistentHashRing.java` | Implemented — 150 vnodes, SHA-256, TreeMap ring, addNode/removeNode |
| Part 6: Read Path (GET) | `controller/StorageGatewayController.java` (`getObject`) | Implemented — metadata lookup → fetch all shards → present[] tracking → decode → checksum verify → 200 or 409 |
| Part 7: Multipart Upload | `multipart/MultipartUploadController.java`, `MultipartUpload.java`, `UploadPart.java`, `UploadPartRepository.java`, `MultipartUploadRepository.java` | Implemented — 4-step S3 protocol, per-part erasure coding, idempotent retry, composite ETag, abort |
| Part 7: GC Service | — | Not yet implemented — blog describes architecture (lifecycle cleanup of abandoned uploads) |
| Part 8: Scrubber | — | Not yet implemented — blog describes architecture (per-node checksum verification) |
| Part 8: Repair Orchestrator | — | Not yet implemented — blog describes architecture (detect → reconstruct → write replacement → update metadata) |
| Part 8: Durability Math | — | Architecture only — MTTR × AFR probability model, no corresponding code |
| Part 9: Failure Modes | — | Architecture only — no failure injection code |

## Key Implementation Details

### Erasure Coding (blog Part 4 → `ErasureCodingService.java`)

- GF(2^8) primitive polynomial: `0x11D` (x^8 + x^4 + x^3 + x^2 + 1)
- EXP/LOG tables: 512/256 entries, precomputed in static initializer
- Multiplication: `gfMul(a, b) = EXP[LOG[a] + LOG[b]]` — O(1) per byte
- Generator matrix: k=4 identity rows + m=2 Vandermonde rows
  - Row 4 = [1, 1, 1, 1] (XOR of all data shards)
  - Row 5 = [1, 2, 4, 8] (α^0, α^1, α^2, α^3 where α=2)
- Decode: select k available shards → extract submatrix → invert via Gaussian elimination in GF(2^8) → multiply inverse × shard bytes
- Blog code excerpts match the actual source file exactly

### Consistent Hash Ring (blog Part 5 → `ConsistentHashRing.java`)

- 150 virtual nodes per physical node
- Hash function: SHA-256 → first 8 bytes as signed long
- Ring data structure: `TreeMap<Long, Integer>` (hash → physical nodeId)
- `getNode(shardId)`: hash → tailMap → first key (clockwise walk) → wrap to firstKey if past end
- Placement stored at write time in `ObjectMetadata.shardLocations` — never re-derived from ring on GET
- Blog code excerpts match the actual source file exactly

### Multipart Protocol (blog Part 7 → `MultipartUploadController.java`)

- Initiate: creates `multipart_upload` row with status INITIATED
- Upload part: erasure-codes part independently, stores per-part shard map in `upload_part` row, returns ETag
- Idempotent retry: detects existing part via UNIQUE(upload_id, part_number), deletes old shards before overwrite
- Complete: verifies manifest ETags, assembles composite `object_metadata` row, computes S3-style composite ETag
- Abort: deletes all part shards, marks ABORTED
- Blog code excerpts match the actual source file exactly

### Storage-First, Metadata-Second (blog Part 3 → `StorageGatewayController.putObject()`)

- Gateway writes shards to storage nodes (step 3) before persisting metadata (step 4)
- Crash between 3 and 4 → orphaned shards (harmless, cleaned by future GC service)
- Reverse ordering → metadata references missing shards (correctness failure)
- Blog explains both failure scenarios; code implements the correct ordering

### Checksum Verification (blog Part 6 → `StorageGatewayController.getObject()`)

- MD5 computed on PUT, stored in `ObjectMetadata.checksum`
- On every GET: MD5 of reconstructed bytes compared to stored checksum
- Mismatch → HTTP 409 Conflict (not 200 with corrupt data)
- Blog explains the three things checksum catches: bit rot, decode bugs, metadata corruption
