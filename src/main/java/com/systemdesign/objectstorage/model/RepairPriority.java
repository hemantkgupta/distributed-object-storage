package com.systemdesign.objectstorage.model;

/**
 * Repair priority based on remaining shard count.
 * Objects closer to data loss are repaired first.
 */
public enum RepairPriority {
    /** 4 of 6 shards remaining — one more failure = unrecoverable. */
    CRITICAL,
    /** 5 of 6 shards remaining — two failures away from loss. */
    HIGH,
    /** 6 of 6 shards — scrub-detected issue, no immediate risk. */
    NORMAL
}
