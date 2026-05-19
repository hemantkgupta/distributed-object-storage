package com.systemdesign.objectstorage.scrubber;

/**
 * Summary of a scrub pass over one storage node.
 *
 * @param shardsScanned total shards checked in this pass
 * @param clean         shards with matching checksums
 * @param corrupt       shards with mismatched checksums (repair tasks created)
 * @param missing       shards expected but not found on disk
 * @param errors        shards that could not be read (I/O errors)
 * @param resumeCursor  the last shard ID processed (null if scan completed the full inventory)
 */
public record ScrubSummary(int shardsScanned, int clean, int corrupt, int missing, int errors, String resumeCursor) {

    public boolean isComplete() {
        return resumeCursor == null;
    }

    public int unhealthy() {
        return corrupt + missing + errors;
    }
}
