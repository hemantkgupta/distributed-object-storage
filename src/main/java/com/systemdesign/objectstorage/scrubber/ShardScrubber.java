package com.systemdesign.objectstorage.scrubber;

/**
 * Background integrity verification for a single storage node.
 *
 * <p>The scrubber walks the node's shard inventory, computes checksums,
 * compares them to expected values from the metadata store, and creates
 * repair tasks for any mismatches. It uses a persistent cursor to resume
 * after each pass, ensuring every shard is eventually verified.
 */
public interface ShardScrubber {

    /**
     * Runs one scrub pass on the given node, starting from the last cursor position.
     *
     * @param nodeId the node to scrub
     * @param budget limits for this pass (max shards, max duration)
     * @return a summary of what was found
     */
    ScrubSummary scrubNode(int nodeId, ScrubBudget budget);
}
