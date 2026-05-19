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

/**
 * When the consistent hash ring proposes a node that shares a failure domain
 * with an already-assigned node, the policy must walk clockwise (probe further
 * ring positions) until it finds an eligible node. These tests force collisions
 * to verify the walk path.
 */
class TopologyAwarePlacementPolicyCollisionTest {

    private ConsistentHashRing ring;
    private TopologyAwarePlacementPolicy policy;

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ring.addNode(i);
        policy = new TopologyAwarePlacementPolicy(ring);
    }

    @Test
    void placementSucceedsEvenWhenInitialCandidateIsInUsedRack() {
        // Two nodes per rack. The collision walk must find the second rack member
        // when the first is already consumed by an earlier shard.
        List<StorageNodeHealth> nodes = new ArrayList<>();
        Map<Integer, List<FailureDomain>> domains = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            nodes.add(new StorageNodeHealth(i, 1_000_000L, 100_000L, 0, true));
        }
        // 6 nodes in 6 racks → guaranteed 6 distinct racks, but collisions still
        // exercise the walk when two shards hash to the same node initially.
        for (int i = 1; i <= 6; i++) {
            domains.put(i, List.of(new FailureDomain("rack", "rack-" + i)));
        }

        PlacementPlan plan = policy.place("collide-1", 6, nodes, domains);
        assertThat(plan.shardAssignments()).hasSize(6);
    }

    @Test
    void allAssignmentsAreOnDistinctNodesAfterCollisionWalk() {
        List<StorageNodeHealth> nodes = new ArrayList<>();
        Map<Integer, List<FailureDomain>> domains = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            nodes.add(new StorageNodeHealth(i, 1_000_000L, 100_000L, 0, true));
            domains.put(i, List.of(new FailureDomain("rack", "rack-" + i)));
        }

        PlacementPlan plan = policy.place("collide-2", 6, nodes, domains);
        Set<Integer> distinctNodes = new HashSet<>(plan.shardAssignments().values());
        assertThat(distinctNodes).hasSize(6);
    }

    @Test
    void multiDomainNodeCannotShareEitherDomain() {
        // Each node carries both rack and az labels. The policy must avoid
        // sharing on EITHER axis. 6 racks but only 2 AZs → must fail because
        // a single AZ would be reused.
        List<StorageNodeHealth> nodes = new ArrayList<>();
        Map<Integer, List<FailureDomain>> domains = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            nodes.add(new StorageNodeHealth(i, 1_000_000L, 100_000L, 0, true));
            String az = "az-" + ((i - 1) / 3 + 1); // 1,1,1,2,2,2
            domains.put(i, List.of(
                    new FailureDomain("rack", "rack-" + i),
                    new FailureDomain("az", az)));
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> policy.place("collide-3", 6, nodes, domains))
                .isInstanceOf(IllegalStateException.class);
    }
}
