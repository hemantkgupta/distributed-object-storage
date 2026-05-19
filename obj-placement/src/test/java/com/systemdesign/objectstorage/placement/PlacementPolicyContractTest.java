package com.systemdesign.objectstorage.placement;

import com.systemdesign.objectstorage.routing.ConsistentHashRing;
import com.systemdesign.objectstorage.storage.StorageNodeHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests: given the same inputs, the policy must produce the same plan
 * across repeated invocations and across separate policy instances built on
 * identically-seeded rings. Determinism is required so the gateway can recover
 * the placement from {@code object_metadata.shardLocations} without re-running
 * the policy.
 */
class PlacementPolicyContractTest {

    private TopologyAwarePlacementPolicy policy;
    private List<StorageNodeHealth> nodes;
    private Map<Integer, List<FailureDomain>> domainMap;

    @BeforeEach
    void setUp() {
        ConsistentHashRing ring = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ring.addNode(i);
        policy = new TopologyAwarePlacementPolicy(ring);

        nodes = new ArrayList<>();
        domainMap = new HashMap<>();
        for (int i = 1; i <= 6; i++) {
            nodes.add(new StorageNodeHealth(i, 1_000_000L, 100_000L, 0, true));
            domainMap.put(i, List.of(new FailureDomain("rack", "rack-" + i)));
        }
    }

    @Test
    void sameInputsProduceSamePlanAcrossRepeatedCalls() {
        PlacementPlan a = policy.place("obj-X", 6, nodes, domainMap);
        PlacementPlan b = policy.place("obj-X", 6, nodes, domainMap);
        assertThat(a.shardAssignments()).isEqualTo(b.shardAssignments());
    }

    @Test
    void sameInputsProduceSamePlanAcrossSeparatePolicyInstances() {
        ConsistentHashRing ringA = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ringA.addNode(i);
        TopologyAwarePlacementPolicy policyA = new TopologyAwarePlacementPolicy(ringA);

        ConsistentHashRing ringB = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ringB.addNode(i);
        TopologyAwarePlacementPolicy policyB = new TopologyAwarePlacementPolicy(ringB);

        PlacementPlan a = policyA.place("obj-Y", 6, nodes, domainMap);
        PlacementPlan b = policyB.place("obj-Y", 6, nodes, domainMap);
        assertThat(a.shardAssignments()).isEqualTo(b.shardAssignments());
    }

    @Test
    void differentObjectIdsProduceDifferentPlans() {
        PlacementPlan a = policy.place("obj-A", 6, nodes, domainMap);
        PlacementPlan b = policy.place("obj-B", 6, nodes, domainMap);

        // Plans may share some assignments but should not be entirely identical —
        // the object id is the hash seed for shard keys.
        assertThat(a.shardAssignments()).isNotEqualTo(b.shardAssignments());
    }
}
