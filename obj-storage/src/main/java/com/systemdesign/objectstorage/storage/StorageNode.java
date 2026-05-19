package com.systemdesign.objectstorage.storage;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction over a single storage node's shard I/O operations.
 *
 * <p>A storage node holds raw shard bytes on local disk. It knows nothing about
 * objects, buckets, erasure coding, or metadata — it reads and writes byte arrays
 * identified by physical shard IDs.
 *
 * <p>The contract is intentionally minimal: the scrubber, repair orchestrator,
 * and GC service each compose on top of these primitives.
 */
public interface StorageNode {

    /** Returns the node identifier. */
    int nodeId();

    /** Writes shard bytes to local storage. Overwrites if already present. */
    void writeShard(String physicalShardId, byte[] data) throws IOException;

    /** Reads shard bytes from local storage. */
    byte[] readShard(String physicalShardId) throws IOException;

    /** Deletes a shard from local storage. No-op if the shard does not exist. */
    void deleteShard(String physicalShardId) throws IOException;

    /** Returns true if the shard exists on local storage. */
    boolean shardExists(String physicalShardId);

    /**
     * Lists all physical shard IDs on this node.
     * Used by the scrubber to walk the full shard inventory.
     */
    List<String> listShards() throws IOException;

    /**
     * Computes and returns the MD5 checksum of a shard's bytes.
     * Used by the scrubber for integrity verification.
     */
    byte[] shardChecksum(String physicalShardId) throws IOException;

    /** Returns a health snapshot of this node. */
    StorageNodeHealth health();
}
