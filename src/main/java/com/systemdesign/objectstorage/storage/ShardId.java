package com.systemdesign.objectstorage.storage;

/**
 * Globally unique identifier for a shard on a specific storage node.
 *
 * @param nodeId         the storage node holding this shard
 * @param physicalId     the on-disk file name (e.g., "{baseShardId}-shard-{index}")
 */
public record ShardId(int nodeId, String physicalId) {
    public ShardId {
        if (physicalId == null || physicalId.isBlank()) {
            throw new IllegalArgumentException("physical shard id must not be blank");
        }
    }
}
