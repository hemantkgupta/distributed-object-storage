package com.systemdesign.objectstorage.scrubber;

import com.systemdesign.objectstorage.model.*;
import com.systemdesign.objectstorage.repair.RepairTaskQueue;
import com.systemdesign.objectstorage.storage.ShardId;
import com.systemdesign.objectstorage.storage.StorageNode;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Background scrubber that walks a storage node's shard inventory, verifies
 * checksums against expected values, and enqueues repair tasks for any
 * mismatches or missing shards.
 *
 * <p>Design choices from Ceph/MinIO research:
 * <ul>
 *   <li>Persistent cursor: resumes from the last position on each pass, not from the beginning</li>
 *   <li>Budgeted: stops after maxShardsPerRun to avoid saturating disk I/O</li>
 *   <li>Creates RepairTasks for corrupt/missing shards — does NOT delete or repair inline</li>
 * </ul>
 */
public class BackgroundScrubber implements ShardScrubber {

    private final Map<Integer, StorageNode> storageNodes;
    private final Function<String, byte[]> expectedChecksumLookup;
    private final RepairTaskQueue repairTaskQueue;

    // Persistent cursor: last scanned shard ID per node
    private final Map<Integer, String> cursors = new HashMap<>();

    /**
     * @param storageNodes           node ID → StorageNode
     * @param expectedChecksumLookup physicalShardId → expected MD5 bytes (from metadata).
     *                               Returns null if shard is not referenced by any metadata.
     * @param repairTaskQueue        queue to enqueue repair tasks for corrupt/missing shards
     */
    public BackgroundScrubber(Map<Integer, StorageNode> storageNodes,
                               Function<String, byte[]> expectedChecksumLookup,
                               RepairTaskQueue repairTaskQueue) {
        this.storageNodes = storageNodes;
        this.expectedChecksumLookup = expectedChecksumLookup;
        this.repairTaskQueue = repairTaskQueue;
    }

    @Override
    public ScrubSummary scrubNode(int nodeId, ScrubBudget budget) {
        StorageNode node = storageNodes.get(nodeId);
        if (node == null) {
            return new ScrubSummary(0, 0, 0, 0, 0, null);
        }

        List<String> allShards;
        try {
            allShards = node.listShards();
        } catch (Exception e) {
            return new ScrubSummary(0, 0, 0, 0, 1, null);
        }

        // Sort for deterministic ordering; resume from cursor
        List<String> sorted = new ArrayList<>(allShards);
        Collections.sort(sorted);

        String cursor = cursors.get(nodeId);
        int startIndex = 0;
        if (cursor != null) {
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).compareTo(cursor) > 0) {
                    startIndex = i;
                    break;
                }
                if (i == sorted.size() - 1) {
                    startIndex = 0; // wrapped around — start from beginning
                    cursor = null;
                }
            }
        }

        Instant deadline = Instant.now().plus(budget.maxDuration());
        int scanned = 0, clean = 0, corrupt = 0, missing = 0, errors = 0;
        String lastScanned = null;

        for (int i = 0; i < sorted.size() && scanned < budget.maxShardsPerRun(); i++) {
            if (Instant.now().isAfter(deadline)) break;

            int idx = (startIndex + i) % sorted.size();
            String shardId = sorted.get(idx);
            scanned++;
            lastScanned = shardId;

            ScrubResult result = scrubSingleShard(node, shardId);

            switch (result.outcome()) {
                case CLEAN -> clean++;
                case CHECKSUM_MISMATCH -> {
                    corrupt++;
                    enqueueRepairTask(nodeId, shardId);
                }
                case MISSING -> {
                    missing++;
                    enqueueRepairTask(nodeId, shardId);
                }
                case READ_ERROR -> errors++;
            }
        }

        // Update cursor for next pass
        boolean scanComplete = (scanned >= sorted.size());
        if (scanComplete) {
            cursors.remove(nodeId);
        } else {
            cursors.put(nodeId, lastScanned);
        }

        return new ScrubSummary(scanned, clean, corrupt, missing, errors,
                scanComplete ? null : lastScanned);
    }

    private ScrubResult scrubSingleShard(StorageNode node, String shardId) {
        Instant now = Instant.now();
        ShardId sid = new ShardId(node.nodeId(), shardId);

        if (!node.shardExists(shardId)) {
            return new ScrubResult(sid, ScrubOutcome.MISSING, now, "shard file not found on disk");
        }

        byte[] actualChecksum;
        try {
            actualChecksum = node.shardChecksum(shardId);
        } catch (Exception e) {
            return new ScrubResult(sid, ScrubOutcome.READ_ERROR, now, e.getMessage());
        }

        byte[] expectedChecksum = expectedChecksumLookup.apply(shardId);
        if (expectedChecksum == null) {
            // No metadata reference — this might be an orphan, but that's GC's job, not scrubber's
            return new ScrubResult(sid, ScrubOutcome.CLEAN, now, "no metadata reference — skipping checksum comparison");
        }

        if (Arrays.equals(actualChecksum, expectedChecksum)) {
            return new ScrubResult(sid, ScrubOutcome.CLEAN, now, null);
        } else {
            return new ScrubResult(sid, ScrubOutcome.CHECKSUM_MISMATCH, now,
                    "expected " + Base64.getEncoder().encodeToString(expectedChecksum)
                            + " got " + Base64.getEncoder().encodeToString(actualChecksum));
        }
    }

    private void enqueueRepairTask(int nodeId, String shardId) {
        Instant now = Instant.now();
        RepairTask task = new RepairTask(
                UUID.randomUUID().toString(),
                shardId, // objectId will be resolved by repair orchestrator from shard naming
                -1,      // shard index unknown at scrub time
                RepairPriority.HIGH,
                now,
                now,     // due immediately
                0
        );
        repairTaskQueue.enqueue(task);
    }
}
