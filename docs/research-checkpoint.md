# Research Checkpoint

## Direction

Build a distributed object storage system inspired by Amazon S3's architecture: a flat key-value blob store with erasure coding for cost-efficient durability, a metadata/data plane split for I/O isolation, consistent hashing for shard placement, and multipart upload for large objects over unreliable networks.

The primary learning target is erasure coding economics and the metadata/data plane separation — understanding why hyperscale storage systems spend engineering effort on complex math (Reed-Solomon) rather than simple duplication (replication), and why tracking where shards live is architecturally separate from holding the shards themselves.

## Foundation

- Object API: `PUT`, `GET`, `DELETE`, multipart upload (initiate, upload part, complete, abort).
- Metadata/data plane split: metadata is tiny per object but queried billions of times per day; data is huge but accessed infrequently per individual blob.
- Erasure coding: Reed-Solomon over GF(2^8). k data shards + m parity shards; any k of k+m reconstruct the original.
- Consistent hashing: SHA-256-derived tokens, virtual nodes for uniform distribution, clockwise placement.
- Immutability: objects cannot be modified in place — erasure coding invalidates parity on any byte change.

## Going Deeper

- GF(2^8) arithmetic: finite field with 256 elements, addition is XOR, multiplication via exp/log tables from primitive polynomial 0x11D.
- Generator matrix: identity rows (data shards pass through) + Vandermonde rows (parity computation).
- Degraded reads: when shards are missing, select any k available, invert the corresponding submatrix, multiply by available shard bytes. Same CPU cost as encoding.
- Checksum on every read: the decoder doesn't know its input is corrupt — it produces output from garbage. The MD5 checksum is the only defense against silent corruption producing wrong results.
- Per-part erasure coding in multipart: avoids holding the entire object in memory. Each part is self-contained — encoded, placed, and stored independently.
- Placement stored at write time: ring changes must not break reads. The metadata record always reflects actual shard locations.
- Storage-first, metadata-second: orphaned shards are harmless; metadata referencing missing shards is a correctness failure.

## At Scale

- Durability math: with (4,2) coding and 2% AFR, durability depends on MTTR. 1-hour MTTR → 11 nines; 24-hour MTTR → 8 nines. Fast automated repair is not an optimization — it is the durability mechanism.
- Background scrubbing: verify every shard weekly. Detect bit rot before it accumulates past the erasure coding tolerance.
- Repair orchestration: detect → reconstruct from survivors → write replacement → update metadata. Priority queue by remaining shard count.
- Failure domain placement: no two shards of the same object on the same rack/power domain. Without this, a rack failure can destroy multiple shards and exceed the tolerance.
- Encoding profiles: (4,2) for hot data (low read amplification), (14,4) for cold archival (minimal storage cost). The system should support configurable profiles per storage tier.
- GC for orphaned data: shards without metadata references (from gateway crashes) and abandoned multipart uploads (client never called complete) must be cleaned periodically.

## Recommended Defaults

- Encoding profile: (4,2) for general-purpose storage. Balanced read amplification and storage cost.
- Checksum: MD5 on every PUT, verified on every GET. Non-negotiable.
- Virtual nodes: 150 per physical node. Provides statistically uniform distribution across 6+ nodes.
- Multipart part size: 100 MB chunks for uploads > 100 MB. Balances retry granularity and decode overhead.
- Tombstone / lifecycle: orphaned shards retained 24 hours before GC. Abandoned uploads aborted after 7 days.
- MTTR target: 1 hour or less for automated repair. This is where 11 nines comes from.

## Wiki References

- `concepts/erasure-coding` — Reed-Solomon math, encoding profiles, cost tradeoffs
- `concepts/consistent-hashing` — token ring, virtual nodes, minimal reshuffling on topology change
- `concepts/multipart-upload` — S3-compatible chunked upload protocol
- `concepts/object-storage` — metadata/data plane split, immutability, blob storage semantics
