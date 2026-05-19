package com.systemdesign.objectstorage.scrubber;

import com.systemdesign.objectstorage.storage.StorageNode;
import com.systemdesign.objectstorage.storage.StorageNodeHealth;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-rolled in-memory StorageNode used by scrubber tests. Supports marking
 * specific shards as "missing" (file gone) for the MISSING-outcome path.
 */
final class InMemoryStorageNode implements StorageNode {

    private final int nodeId;
    private final Map<String, byte[]> shards = new HashMap<>();
    private final Set<String> deletedShards = new HashSet<>();

    InMemoryStorageNode(int nodeId) {
        this.nodeId = nodeId;
    }

    void putShard(String physicalShardId, byte[] data) {
        shards.put(physicalShardId, data);
        deletedShards.remove(physicalShardId);
    }

    void simulateMissing(String physicalShardId) {
        // List still returns the shard (the scrubber walks listShards() and then
        // re-checks shardExists), but shardExists returns false.
        deletedShards.add(physicalShardId);
    }

    @Override public int nodeId() { return nodeId; }

    @Override public void writeShard(String id, byte[] data) {
        shards.put(id, data);
        deletedShards.remove(id);
    }

    @Override public byte[] readShard(String id) throws IOException {
        if (deletedShards.contains(id)) throw new IOException("missing: " + id);
        byte[] data = shards.get(id);
        if (data == null) throw new IOException("no such shard: " + id);
        return data;
    }

    @Override public void deleteShard(String id) {
        shards.remove(id);
        deletedShards.remove(id);
    }

    @Override public boolean shardExists(String id) {
        return shards.containsKey(id) && !deletedShards.contains(id);
    }

    @Override public List<String> listShards() {
        return new ArrayList<>(shards.keySet());
    }

    @Override public byte[] shardChecksum(String id) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(readShard(id));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public StorageNodeHealth health() {
        return new StorageNodeHealth(nodeId, 1_000_000L, 0L, shards.size(), true);
    }
}
