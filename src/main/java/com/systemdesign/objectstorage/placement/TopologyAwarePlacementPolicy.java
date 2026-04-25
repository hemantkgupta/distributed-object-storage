package com.systemdesign.objectstorage.placement;

import com.systemdesign.objectstorage.routing.ConsistentHashRing;
import com.systemdesign.objectstorage.storage.StorageNodeHealth;

import java.util.*;

/**
 * Placement policy that wraps the consistent hash ring with failure domain enforcement.
 *
 * <p>For each shard, the ring proposes a candidate node. If that node shares a failure
 * domain with a node already assigned a shard of the same object, the policy walks
 * further clockwise until it finds an eligible node. If no eligible node exists,
 * the placement fails rather than placing unsafely.
 *
 * <p>This implements the Ceph CRUSH principle: the placement contract must reject
 * or downgrade placements that cannot satisfy domain constraints.
 */
public class TopologyAwarePlacementPolicy implements PlacementPolicy {

    private final ConsistentHashRing hashRing;

    public TopologyAwarePlacementPolicy(ConsistentHashRing hashRing) {
        if (hashRing == null) {
            throw new IllegalArgumentException("hash ring must not be null");
        }
        this.hashRing = hashRing;
    }

    @Override
    public PlacementPlan place(String objectId, int totalShards,
                               List<StorageNodeHealth> candidates,
                               Map<Integer, List<FailureDomain>> domainMap) {
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("object id must not be blank");
        }
        if (totalShards <= 0) {
            throw new IllegalArgumentException("total shards must be positive");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty");
        }

        Set<Integer> healthyNodeIds = new HashSet<>();
        for (StorageNodeHealth node : candidates) {
            if (node.healthy()) {
                healthyNodeIds.add(node.nodeId());
            }
        }

        Map<Integer, Integer> assignments = new LinkedHashMap<>();
        Set<Integer> usedNodeIds = new HashSet<>();
        Set<String> usedDomainValues = new HashSet<>();
        List<String> warnings = new ArrayList<>();

        for (int shardIndex = 0; shardIndex < totalShards; shardIndex++) {
            String shardKey = objectId + "-shard-" + shardIndex;
            int candidate = hashRing.getNode(shardKey);

            // Walk clockwise looking for a node that:
            // 1. Is healthy
            // 2. Is not already assigned a shard of this object
            // 3. Does not share a failure domain with any already-assigned node
            int assigned = findEligibleNode(candidate, shardKey, healthyNodeIds,
                    usedNodeIds, usedDomainValues, domainMap);

            if (assigned < 0) {
                throw new IllegalStateException(
                        "Cannot place shard " + shardIndex + " for object " + objectId
                                + ": no eligible node satisfies failure domain constraints. "
                                + "Need " + totalShards + " distinct failure domains, "
                                + "only " + healthyNodeIds.size() + " healthy nodes available.");
            }

            assignments.put(shardIndex, assigned);
            usedNodeIds.add(assigned);

            // Track failure domains of this assigned node
            List<FailureDomain> domains = domainMap.getOrDefault(assigned, List.of());
            for (FailureDomain fd : domains) {
                usedDomainValues.add(fd.type() + ":" + fd.value());
            }

            // Warn if node is above 80% capacity
            for (StorageNodeHealth node : candidates) {
                if (node.nodeId() == assigned && node.usageRatio() > 0.80) {
                    warnings.add("Node " + assigned + " is at "
                            + String.format("%.0f%%", node.usageRatio() * 100) + " capacity");
                }
            }
        }

        return new PlacementPlan(assignments, warnings);
    }

    /**
     * Starting from the ring's candidate, walks clockwise to find a node that
     * satisfies health, uniqueness, and failure domain constraints.
     * Returns -1 if no eligible node found.
     */
    private int findEligibleNode(int ringCandidate, String shardKey,
                                  Set<Integer> healthyNodeIds,
                                  Set<Integer> usedNodeIds,
                                  Set<String> usedDomainValues,
                                  Map<Integer, List<FailureDomain>> domainMap) {
        // Try the ring's primary candidate first
        if (isEligible(ringCandidate, healthyNodeIds, usedNodeIds, usedDomainValues, domainMap)) {
            return ringCandidate;
        }

        // Walk clockwise: try alternative shard keys to probe further ring positions
        for (int attempt = 1; attempt <= healthyNodeIds.size() * 3; attempt++) {
            String probeKey = shardKey + "-probe-" + attempt;
            int probeCandidate = hashRing.getNode(probeKey);
            if (isEligible(probeCandidate, healthyNodeIds, usedNodeIds, usedDomainValues, domainMap)) {
                return probeCandidate;
            }
        }

        return -1;
    }

    private boolean isEligible(int nodeId, Set<Integer> healthyNodeIds,
                                Set<Integer> usedNodeIds, Set<String> usedDomainValues,
                                Map<Integer, List<FailureDomain>> domainMap) {
        if (!healthyNodeIds.contains(nodeId)) return false;
        if (usedNodeIds.contains(nodeId)) return false;

        // Check failure domain constraint
        List<FailureDomain> domains = domainMap.getOrDefault(nodeId, List.of());
        for (FailureDomain fd : domains) {
            String domainKey = fd.type() + ":" + fd.value();
            if (usedDomainValues.contains(domainKey)) {
                return false; // shares a failure domain with an already-assigned node
            }
        }

        return true;
    }
}
