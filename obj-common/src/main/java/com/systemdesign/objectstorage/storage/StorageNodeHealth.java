package com.systemdesign.objectstorage.storage;

/**
 * Health snapshot of a storage node, used by the placement policy and repair scheduler.
 *
 * @param nodeId       the node identifier
 * @param capacityBytes total disk capacity
 * @param usedBytes    disk space currently used by shards
 * @param shardCount   number of shards stored on this node
 * @param healthy      whether the node is accepting reads and writes
 */
public record StorageNodeHealth(int nodeId, long capacityBytes, long usedBytes, int shardCount, boolean healthy) {
    public long freeBytes() {
        return capacityBytes - usedBytes;
    }

    public double usageRatio() {
        if (capacityBytes == 0) return 1.0;
        return (double) usedBytes / capacityBytes;
    }
}
