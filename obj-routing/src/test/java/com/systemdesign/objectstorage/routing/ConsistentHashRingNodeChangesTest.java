package com.systemdesign.objectstorage.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for ring behaviour during node changes: addNode redistributes only
 * a fraction of shards; removeNode hands off the removed node's shards to the
 * next clockwise node without disturbing unrelated assignments.
 */
class ConsistentHashRingNodeChangesTest {

    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) {
            ring.addNode(i);
        }
    }

    @Test
    void addingNodeMovesOnlyAFractionOfShards() {
        int total = 5_000;
        Map<String, Integer> before = snapshot(total);
        ring.addNode(7);

        int moved = countMoved(total, before);

        // With 6→7 nodes, theoretical movement is ~1/7 (~14%). Allow generous bounds
        // but assert it is strictly less than 1/3 — the load-bearing property of
        // consistent hashing is that adding a node does NOT trigger a full remap.
        assertThat(moved).isLessThan(total / 3);
    }

    @Test
    void addingNodeMovesAtLeastSomeShards() {
        int total = 5_000;
        Map<String, Integer> before = snapshot(total);
        ring.addNode(7);

        int moved = countMoved(total, before);
        assertThat(moved).isPositive();
    }

    @Test
    void removingNodeReassignsItsShardsToOtherNodes() {
        int total = 5_000;
        Map<String, Integer> before = snapshot(total);
        ring.removeNode(3);

        for (Map.Entry<String, Integer> e : before.entrySet()) {
            if (e.getValue() == 3) {
                assertThat(ring.getNode(e.getKey())).isNotEqualTo(3);
            }
        }
    }

    @Test
    void removingNodeLeavesUnrelatedShardsAlone() {
        int total = 5_000;
        Map<String, Integer> before = snapshot(total);
        ring.removeNode(3);

        long disturbed = before.entrySet().stream()
                .filter(e -> e.getValue() != 3)
                .filter(e -> ring.getNode(e.getKey()) != e.getValue())
                .count();

        // Shards not on node 3 must remain on their original node — consistent
        // hashing's locality guarantee.
        assertThat(disturbed).isZero();
    }

    private Map<String, Integer> snapshot(int total) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < total; i++) {
            String shard = "shard-" + i;
            map.put(shard, ring.getNode(shard));
        }
        return map;
    }

    private int countMoved(int total, Map<String, Integer> before) {
        int moved = 0;
        for (int i = 0; i < total; i++) {
            String shard = "shard-" + i;
            if (ring.getNode(shard) != before.get(shard)) moved++;
        }
        return moved;
    }
}
