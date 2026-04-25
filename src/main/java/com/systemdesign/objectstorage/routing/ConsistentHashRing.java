package com.systemdesign.objectstorage.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Consistent Hashing Ring for shard-to-node assignment.
 *
 * Design:
 *   - Physical nodes (e.g. 1..6) are mapped onto a 2^64 hash ring.
 *   - Each physical node is represented by VIRTUAL_NODES_PER_NODE virtual
 *     replicas to smooth the load distribution.
 *   - To place a shard: hash the physicalShardId, walk clockwise to the first
 *     virtual node, return its physical nodeId.
 *
 * Why this matters:
 *   Static i+1 assignment hardcodes the coupling between shard index and node.
 *   With a consistent hash ring, adding or removing a node redistributes only
 *   k/N shards (where k = total shards, N = nodes), instead of requiring a
 *   full remap. The shardLocations JSON stored in ObjectMetadata records the
 *   placement at write time, so reads always use the map — not live ring state.
 */
@Component
public class ConsistentHashRing {

    /** Number of virtual nodes (replicas) per physical node. */
    private static final int VIRTUAL_NODES_PER_NODE = 150;

    /** Sorted ring: hash → physicalNodeId */
    private final SortedMap<Long, Integer> ring = new TreeMap<>();

    @Value("${storage.nodes}")
    private List<Integer> nodeIds;

    @PostConstruct
    public void buildRing() {
        for (int nodeId : nodeIds) {
            for (int replica = 0; replica < VIRTUAL_NODES_PER_NODE; replica++) {
                String virtualKey = "node-" + nodeId + "-vnode-" + replica;
                long hash = hash(virtualKey);
                ring.put(hash, nodeId);
            }
        }
    }

    /**
     * Returns the physical nodeId responsible for the given shardId.
     * Hashes the shardId, finds the first virtual node clockwise on the ring.
     */
    public int getNode(String shardId) {
        if (ring.isEmpty()) {
            throw new IllegalStateException("Consistent hash ring is empty — no nodes registered");
        }
        long hash = hash(shardId);
        SortedMap<Long, Integer> tailMap = ring.tailMap(hash);
        // Wrap around the ring if we've passed the last virtual node
        Long key = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        return ring.get(key);
    }

    /**
     * Adds a node to the ring (used when a new storage node comes online).
     * Existing shardLocations in DB are unaffected — they recorded placement at
     * write time. New writes will start distributing shards to this node.
     */
    public void addNode(int nodeId) {
        for (int replica = 0; replica < VIRTUAL_NODES_PER_NODE; replica++) {
            String virtualKey = "node-" + nodeId + "-vnode-" + replica;
            ring.put(hash(virtualKey), nodeId);
        }
    }

    /**
     * Removes a node from the ring (used when a node is decommissioned).
     * In-flight shards already written to this node remain readable via
     * shardLocations until the background rebalancer migrates them.
     */
    public void removeNode(int nodeId) {
        for (int replica = 0; replica < VIRTUAL_NODES_PER_NODE; replica++) {
            String virtualKey = "node-" + nodeId + "-vnode-" + replica;
            ring.remove(hash(virtualKey));
        }
    }

    /** Returns the current number of virtual nodes on the ring. */
    public int size() {
        return ring.size();
    }

    // ── SHA-256 → long (first 8 bytes) ──────────────────────────────────────

    private static long hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            // Take first 8 bytes as a signed long — enough entropy for 2^63 positions
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (bytes[i] & 0xFF);
            }
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
