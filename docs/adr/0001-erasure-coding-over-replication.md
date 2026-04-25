# ADR 0001: Erasure Coding over Replication

## Status

Accepted for initial implementation.

## Decision

Use Reed-Solomon erasure coding (4,2) as the primary redundancy mechanism, implemented self-contained in Java without external libraries. Store shard placement at write time in the metadata record, not re-derived from the hash ring on read. Erasure-code each multipart upload part independently at upload time.

## Context

The project requires storing objects durably across multiple storage nodes such that any two simultaneous node failures are survivable. Three redundancy strategies were considered:

1. **3-way replication**: Copy every object to 3 independent nodes. Simple, proven (HDFS). Storage overhead: 3.0×. At exabyte scale, the cost difference versus erasure coding is billions of dollars in hardware.

2. **Reed-Solomon erasure coding (4,2)**: Split into 4 data shards + 2 parity shards. Any 4 of 6 reconstruct the original. Storage overhead: 1.5×. Same dual-failure tolerance as 3-way replication at half the cost.

3. **External erasure coding library** (e.g., `backblaze/JavaReedSolomon`): Use a battle-tested open-source Reed-Solomon implementation instead of writing the math from scratch.

## Rationale

### Erasure coding (4,2) over 3-way replication

The storage cost difference is the deciding factor. At 1 exabyte of user data: replication requires 3 EB of physical storage; erasure coding (4,2) requires 1.5 EB. Both tolerate 2 simultaneous failures. The tradeoff — CPU for encode/decode on every PUT and GET — is acceptable because storage cost dominates at scale; CPU and network are comparatively cheap.

A (4,2) profile was chosen over more aggressive profiles like (14,4) because read amplification matters for a general-purpose store. A (14,4) profile requires fetching 14 shards to serve one GET; (4,2) requires only 4. For hot data with frequent reads, this difference is significant. (14,4) is appropriate for cold archival tiers where reads are rare.

### Self-contained GF(2^8) implementation over external library

The implementation hand-builds the Galois Field arithmetic from the primitive polynomial (0x11D), the exp/log tables, the Vandermonde generator matrix, and the Gaussian elimination matrix inversion. This was a deliberate choice:

- **No external dependency**: The erasure coding service is zero-dependency. No native bindings, no JNI, no version conflicts.
- **Full understanding**: Every line of the encoder and decoder can be explained in the blog. The generator matrix construction, the GF multiplication via table lookup, and the matrix inversion during degraded reads are all visible and documented.
- **Educational value**: This project is a companion to a system design blog. The purpose is not just to work but to teach how it works.

The downside: no SIMD-optimized encoding, no multi-threaded shard computation. For a production hyperscale system, a native-optimized library would be appropriate. For this project's throughput requirements, pure-Java table-lookup GF(2^8) is sufficient.

### Placement stored at write time

When a shard is placed on a node, the assignment is recorded in the `shard_locations` JSON column of the `object_metadata` row. GET and DELETE read this stored map; they never re-derive placement from the consistent hash ring.

This avoids a correctness bug: if a node is added or removed from the ring between write time and read time, re-deriving placement would point to the wrong node. The stored map always reflects where the shards actually live. A background rebalancer can migrate shards and update the map over time, but reads always work immediately.

### Per-part erasure coding in multipart upload

Each multipart upload part is independently erasure-coded at upload time. The alternative — accumulate all parts and erasure-code the concatenated object on complete — would require holding the entire object (potentially terabytes) in memory during the complete step. Per-part coding eliminates this: each 100 MB part requires only 100 MB of memory, and parts can be uploaded and coded in parallel.

The tradeoff: reads of multipart objects must decode each part sequentially. For a 5 TB object with 50,000 parts, this adds overhead proportional to the part count. This is acceptable because multipart objects are inherently large and read-intensive workloads for large objects are throughput-bound, not latency-bound.

### Storage-first, metadata-second ordering

On PUT, the gateway writes shards to storage nodes before persisting the metadata record. If the gateway crashes between the two steps, orphaned shards exist on storage nodes with no metadata pointing to them. A GC service cleans these periodically.

The reverse — metadata-first, storage-second — would leave a metadata record pointing to shards that don't exist. A subsequent GET would find the metadata, attempt to fetch shards, and fail. The user sees "object exists but is unreadable" — a correctness failure worse than an orphaned shard that wastes temporary space.

## Consequences

- All redundancy math is visible in one file (`ErasureCodingService.java`), making the blog's explanation directly traceable to code.
- Degraded reads (missing shards) incur matrix inversion cost — roughly the same CPU as an encode.
- Checksum verification on every GET catches silent bit rot that the erasure decoder cannot detect (the decoder doesn't know its input is corrupt; it dutifully produces wrong output).
- The metadata store becomes a hard dependency: if it's unavailable, no reads or writes can proceed. This is the primary availability bottleneck.
