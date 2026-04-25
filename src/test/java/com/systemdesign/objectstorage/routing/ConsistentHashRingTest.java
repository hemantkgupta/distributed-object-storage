package com.systemdesign.objectstorage.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the consistent hash ring.
 *
 * Covers: deterministic assignment, distribution uniformity, node addition
 * moves only ~1/N shards, node removal, empty ring, and ring wraparound.
 */
class ConsistentHashRingTest {

    private ConsistentHashRing ring;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing();
        // Simulate @PostConstruct with 6 nodes
        for (int nodeId = 1; nodeId <= 6; nodeId++) {
            ring.addNode(nodeId);
        }
    }

    // ── Determinism ───────────────────────────────────────────────────

    @Test
    void getNode_sameShardId_alwaysReturnsSameNode() {
        String shardId = "test-shard-abc-123";
        int firstResult = ring.getNode(shardId);
        for (int i = 0; i < 100; i++) {
            assertEquals(firstResult, ring.getNode(shardId),
                    "Same shard ID must always map to the same node");
        }
    }

    @Test
    void getNode_differentShardIds_areDeterministic() {
        Map<String, Integer> assignments = new LinkedHashMap<>();
        for (int i = 0; i < 1000; i++) {
            String shardId = "shard-" + UUID.randomUUID();
            assignments.put(shardId, ring.getNode(shardId));
        }

        // Verify every assignment is reproducible
        for (Map.Entry<String, Integer> entry : assignments.entrySet()) {
            assertEquals(entry.getValue(), ring.getNode(entry.getKey()));
        }
    }

    // ── Distribution Uniformity ───────────────────────────────────────

    @Test
    void getNode_distributesAcrossAllNodes() {
        Set<Integer> nodesUsed = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            nodesUsed.add(ring.getNode("shard-" + i));
        }

        // All 6 nodes should receive at least some shards
        assertEquals(6, nodesUsed.size(), "All 6 nodes should be used");
    }

    @Test
    void getNode_distributionIsReasonablyUniform() {
        Map<Integer, Integer> counts = new HashMap<>();
        int totalShards = 60_000;

        for (int i = 0; i < totalShards; i++) {
            int node = ring.getNode("shard-" + i);
            counts.merge(node, 1, Integer::sum);
        }

        // With 150 vnodes per node and 60K shards across 6 nodes,
        // expect ~10K per node. Allow 30% deviation.
        int expectedPerNode = totalShards / 6;
        double maxDeviation = 0.30;

        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            double deviation = Math.abs(entry.getValue() - expectedPerNode) / (double) expectedPerNode;
            assertTrue(deviation < maxDeviation,
                    "Node " + entry.getKey() + " has " + entry.getValue()
                            + " shards (expected ~" + expectedPerNode
                            + ", deviation " + String.format("%.1f%%", deviation * 100) + ")");
        }
    }

    // ── Node Addition: Minimal Reshuffling ────────────────────────────

    @Test
    void addNode_movesOnlyFractionOfShards() {
        // Record assignments with 6 nodes
        int totalShards = 10_000;
        Map<String, Integer> before = new HashMap<>();
        for (int i = 0; i < totalShards; i++) {
            String shardId = "shard-" + i;
            before.put(shardId, ring.getNode(shardId));
        }

        // Add node 7
        ring.addNode(7);

        // Count how many shards moved
        int moved = 0;
        for (int i = 0; i < totalShards; i++) {
            String shardId = "shard-" + i;
            if (ring.getNode(shardId) != before.get(shardId)) {
                moved++;
            }
        }

        // Expect roughly 1/7 (~14%) to move. Allow up to 25%.
        double movedFraction = moved / (double) totalShards;
        assertTrue(movedFraction < 0.25,
                "Adding 1 node to 6 should move ~14% of shards, but moved "
                        + String.format("%.1f%%", movedFraction * 100));
        assertTrue(movedFraction > 0.05,
                "Adding a node should move some shards, but moved only "
                        + String.format("%.1f%%", movedFraction * 100));
    }

    @Test
    void addNode_newNodeReceivesShards() {
        ring.addNode(7);

        boolean node7Used = false;
        for (int i = 0; i < 10_000; i++) {
            if (ring.getNode("shard-" + i) == 7) {
                node7Used = true;
                break;
            }
        }
        assertTrue(node7Used, "Newly added node should receive some shards");
    }

    // ── Node Removal ──────────────────────────────────────────────────

    @Test
    void removeNode_redistributesToRemainingNodes() {
        // Record which shards were on node 3
        List<String> shardsOnNode3 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            String shardId = "shard-" + i;
            if (ring.getNode(shardId) == 3) {
                shardsOnNode3.add(shardId);
            }
        }
        assertFalse(shardsOnNode3.isEmpty(), "Node 3 should have some shards before removal");

        // Remove node 3
        ring.removeNode(3);

        // All previously-node-3 shards should now map to other nodes
        for (String shardId : shardsOnNode3) {
            int newNode = ring.getNode(shardId);
            assertNotEquals(3, newNode,
                    "Shard " + shardId + " should not map to removed node 3");
        }
    }

    @Test
    void removeNode_otherShardsMostlyUnaffected() {
        // Record all non-node-3 assignments
        int totalShards = 10_000;
        Map<String, Integer> before = new HashMap<>();
        for (int i = 0; i < totalShards; i++) {
            String shardId = "shard-" + i;
            int node = ring.getNode(shardId);
            if (node != 3) {
                before.put(shardId, node);
            }
        }

        ring.removeNode(3);

        // Count non-node-3 shards that moved
        int moved = 0;
        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            if (ring.getNode(entry.getKey()) != entry.getValue()) {
                moved++;
            }
        }

        // Very few non-node-3 shards should move (ideally zero, but vnodes can cause minor shifts)
        double movedFraction = moved / (double) before.size();
        assertTrue(movedFraction < 0.05,
                "Removing a node should not move shards that weren't on that node, but "
                        + String.format("%.1f%%", movedFraction * 100) + " moved");
    }

    // ── Ring Size ─────────────────────────────────────────────────────

    @Test
    void size_reflectsVirtualNodes() {
        // 6 nodes × 150 vnodes = 900
        assertEquals(900, ring.size());
    }

    @Test
    void addNode_increasesSize() {
        ring.addNode(7);
        assertEquals(1050, ring.size()); // 7 × 150
    }

    @Test
    void removeNode_decreasesSize() {
        ring.removeNode(1);
        assertEquals(750, ring.size()); // 5 × 150
    }

    // ── Edge Cases ────────────────────────────────────────────────────

    @Test
    void getNode_emptyRing_throwsException() {
        ConsistentHashRing emptyRing = new ConsistentHashRing();
        assertThrows(IllegalStateException.class,
                () -> emptyRing.getNode("any-shard"),
                "Empty ring should throw on getNode");
    }

    @Test
    void getNode_singleNode_allShardsGoToThatNode() {
        ConsistentHashRing singleRing = new ConsistentHashRing();
        singleRing.addNode(42);

        for (int i = 0; i < 100; i++) {
            assertEquals(42, singleRing.getNode("shard-" + i));
        }
    }
}
