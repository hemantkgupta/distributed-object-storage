package com.systemdesign.objectstorage.model;

/** Outcome of a repair attempt for a single object. */
public enum RepairOutcome {
    /** Missing shard successfully reconstructed and placed on a new node. */
    REPAIRED,
    /** Not enough surviving shards to reconstruct — data is unrecoverable. */
    INSUFFICIENT_SHARDS,
    /** Another worker already holds the repair lease for this task. */
    LEASE_CONFLICT,
    /** Repair budget (max reads, max writes, max concurrent) exhausted. */
    BUDGET_EXHAUSTED,
    /** Repair attempted but failed (I/O error, placement failure, CAS conflict). */
    FAILED
}
