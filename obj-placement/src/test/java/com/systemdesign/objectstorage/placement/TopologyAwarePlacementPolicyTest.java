package com.systemdesign.objectstorage.placement;

import com.systemdesign.objectstorage.routing.ConsistentHashRing;
import com.systemdesign.objectstorage.storage.StorageNodeHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that the policy honours the failure-domain contract: every shard placed
 * on a distinct failure domain, and placement fails when the topology cannot
 * support {@code totalShards} distinct domains.
 */
class TopologyAwarePlacementPolicyTest {

    private ConsistentHashRing ring;
    private TopologyAwarePlacementPolicy policy;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ring.addNode(i);
        policy = new TopologyAwarePlacementPolicy(ring);
    }

    @Test
    void sixShardsAcrossSixRacksAllLandOnDistinctRacks() {
        List<StorageNodeHealth> nodes = healthyNodes(6);
        Map<Integer, List<FailureDomain>> rackPerNode = oneRackPerNode(6);

        PlacementPlan plan = policy.place("object-1", 6, nodes, rackPerNode);

        Set<String> usedRacks = new HashSet<>();
        for (int nodeId : plan.shardAssignments().values()) {
            for (FailureDomain fd : rackPerNode.get(nodeId)) {
                usedRacks.add(fd.value());
            }
        }
        assertThat(usedRacks).hasSize(6);
    }

    @Test
    void sixShardsAcrossSixRacksAssignSixDistinctNodes() {
        List<StorageNodeHealth> nodes = healthyNodes(6);
        PlacementPlan plan = policy.place("object-2", 6, nodes, oneRackPerNode(6));
        assertThat(new HashSet<>(plan.shardAssignments().values())).hasSize(6);
    }

    @Test
    void sixShardsAcrossThreeRacksIsRejected() {
        // 2 nodes per rack across 3 racks → only 3 distinct failure domains.
        List<StorageNodeHealth> nodes = healthyNodes(6);
        Map<Integer, List<FailureDomain>> threeRacks = new HashMap<>();
        for (int nodeId = 1; nodeId <= 6; nodeId++) {
            String rack = "rack-" + ((nodeId - 1) % 3 + 1);
            threeRacks.put(nodeId, List.of(new FailureDomain("rack", rack)));
        }

        assertThatThrownBy(() -> policy.place("object-3", 6, nodes, threeRacks))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failure domain");
    }

    @Test
    void warningsAreEmptyWhenAllNodesBelowCapacityThreshold() {
        // Use a slightly oversized cluster (8 nodes / 8 racks for 6 shards) so the
        // probe walk has slack to find 6 distinct racks deterministically. The 6=6
        // case is exercised in the "sixShardsAcross..." tests above.
        ConsistentHashRing wideRing = new ConsistentHashRing();
        for (int i = 1; i <= 8; i++) wideRing.addNode(i);
        TopologyAwarePlacementPolicy widePolicy = new TopologyAwarePlacementPolicy(wideRing);

        List<StorageNodeHealth> nodes = new ArrayList<>();
        Map<Integer, List<FailureDomain>> domains = new HashMap<>();
        for (int i = 1; i <= 8; i++) {
            nodes.add(new StorageNodeHealth(i, 1_000_000L, 100_000L, 0, true));
            domains.put(i, List.of(new FailureDomain("rack", "rack-" + i)));
        }

        PlacementPlan plan = widePolicy.place("object-warning-1", 6, nodes, domains);
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void healthyNodesOnlyAreEligible() {
        // 6 nodes, 6 racks, node 3 unhealthy → cannot place 6 shards on 5 racks
        List<StorageNodeHealth> nodes = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            nodes.add(new StorageNodeHealth(i, 1_000_000L, 0L, 0, i != 3));
        }

        assertThatThrownBy(() -> policy.place("object-5", 6, nodes, oneRackPerNode(6)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullObjectIdIsRejected() {
        assertThatThrownBy(() -> policy.place(null, 6, healthyNodes(6), oneRackPerNode(6)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCandidateListIsRejected() {
        assertThatThrownBy(() -> policy.place("o", 6, List.of(), oneRackPerNode(6)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<StorageNodeHealth> healthyNodes(int n) {
        List<StorageNodeHealth> nodes = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            nodes.add(new StorageNodeHealth(i, 1_000_000L, 100_000L, 0, true));
        }
        return nodes;
    }

    private Map<Integer, List<FailureDomain>> oneRackPerNode(int n) {
        Map<Integer, List<FailureDomain>> map = new HashMap<>();
        for (int i = 1; i <= n; i++) {
            map.put(i, List.of(new FailureDomain("rack", "rack-" + i)));
        }
        return map;
    }
}
