package com.systemdesign.objectstorage.model;

/** Outcome of a single shard scrub check. */
public enum ScrubOutcome {
    /** Checksum matches — shard is healthy. */
    CLEAN,
    /** Computed checksum does not match expected — shard is corrupt. */
    CHECKSUM_MISMATCH,
    /** Shard file is missing from disk. */
    MISSING,
    /** I/O error reading the shard — node may be degraded. */
    READ_ERROR
}
