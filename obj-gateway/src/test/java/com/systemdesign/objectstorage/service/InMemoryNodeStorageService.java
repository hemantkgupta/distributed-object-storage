package com.systemdesign.objectstorage.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Test-only in-memory replacement for {@link NodeStorageService}. Keeps shards
 * keyed by (nodeId, physicalShardId) so we don't touch the filesystem.
 */
public final class InMemoryNodeStorageService extends NodeStorageService {

    private final Map<String, byte[]> store = new HashMap<>();
    private final Set<Integer> failingNodes = new HashSet<>();
    public int writes = 0;

    private String key(int nodeId, String shardId) { return nodeId + ":" + shardId; }

    public void failOnNode(int nodeId) { failingNodes.add(nodeId); }

    @Override
    public void writeShard(int nodeId, String shardId, byte[] data) {
        if (failingNodes.contains(nodeId)) {
            throw new RuntimeException("simulated write failure on node " + nodeId);
        }
        store.put(key(nodeId, shardId), data.clone());
        writes++;
    }

    @Override
    public byte[] readShard(int nodeId, String shardId) {
        byte[] data = store.get(key(nodeId, shardId));
        if (data == null) throw new RuntimeException("shard not found: " + key(nodeId, shardId));
        return data;
    }

    @Override
    public void deleteShard(int nodeId, String shardId) {
        store.remove(key(nodeId, shardId));
    }

    @Override
    public boolean shardExists(int nodeId, String shardId) {
        return store.containsKey(key(nodeId, shardId));
    }

    public int storedShardCount() {
        return store.size();
    }

    public void dropShard(int nodeId, String shardId) {
        store.remove(key(nodeId, shardId));
    }

    public Map<String, byte[]> snapshot() {
        return Map.copyOf(store);
    }
}
