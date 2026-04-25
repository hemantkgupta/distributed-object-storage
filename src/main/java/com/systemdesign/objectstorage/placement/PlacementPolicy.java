package com.systemdesign.objectstorage.placement;

import com.systemdesign.objectstorage.storage.StorageNodeHealth;

import java.util.List;
import java.util.Map;

/**
 * Determines which storage node should hold each shard of an object.
 *
 * <p>Production implementations enforce failure domain constraints: no two shards
 * of the same object may be placed on nodes in the same rack, power circuit, or
 * availability zone. Implementations should reject placements that cannot satisfy
 * constraints rather than silently placing unsafely.
 */
public interface PlacementPolicy {

    /**
     * Assigns each shard (0..totalShards-1) to a storage node.
     *
     * @param objectId   unique object identifier (used as hash seed)
     * @param totalShards number of shards (k data + m parity)
     * @param candidates available nodes with health and domain metadata
     * @param domainMap  node ID → list of failure domains for that node
     * @return a placement plan, or throws if constraints cannot be satisfied
     */
    PlacementPlan place(String objectId, int totalShards,
                        List<StorageNodeHealth> candidates,
                        Map<Integer, List<FailureDomain>> domainMap);
}
