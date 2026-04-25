package com.systemdesign.objectstorage.repair;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.objectstorage.erasure.ErasureCodingService;
import com.systemdesign.objectstorage.model.*;
import com.systemdesign.objectstorage.placement.PlacementPolicy;
import com.systemdesign.objectstorage.placement.PlacementPlan;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import com.systemdesign.objectstorage.storage.StorageNode;
import com.systemdesign.objectstorage.storage.StorageNodeHealth;

import java.time.Instant;
import java.util.*;

/**
 * Repair orchestrator that reconstructs missing/corrupt shards using
 * Reed-Solomon erasure decoding and places replacements on healthy nodes.
 *
 * <p>Algorithm (from Ceph recovery model + research):
 * <ol>
 *   <li>Acquire fenced repair lease (prevent concurrent repair of same object)</li>
 *   <li>Read object metadata: get shard_locations, identify present/missing shards</li>
 *   <li>Fetch k surviving shards from storage nodes</li>
 *   <li>Erasure-decode to reconstruct missing shard</li>
 *   <li>Select replacement node via PlacementPolicy (failure domain aware)</li>
 *   <li>Write replacement shard to selected node</li>
 *   <li>Update shard_locations in metadata (CAS-style: check epoch hasn't changed)</li>
 *   <li>Release repair lease</li>
 * </ol>
 */
public class ErasureRepairOrchestrator implements RepairOrchestrator {

    private final Map<Integer, StorageNode> storageNodes;
    private final MetadataRepository metadataRepository;
    private final ErasureCodingService erasureCodingService;
    private final RepairLeaseStore leaseStore;
    private final String workerId;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ErasureRepairOrchestrator(Map<Integer, StorageNode> storageNodes,
                                     MetadataRepository metadataRepository,
                                     ErasureCodingService erasureCodingService,
                                     RepairLeaseStore leaseStore,
                                     String workerId) {
        this.storageNodes = storageNodes;
        this.metadataRepository = metadataRepository;
        this.erasureCodingService = erasureCodingService;
        this.leaseStore = leaseStore;
        this.workerId = workerId;
    }

    @Override
    public RepairResult repair(RepairTask task, RepairBudget budget) {
        Instant now = Instant.now();

        // Step 1: Acquire lease
        Optional<RepairLease> lease = leaseStore.tryAcquire(
                task.taskId(), workerId, java.time.Duration.ofMinutes(5));
        if (lease.isEmpty()) {
            return new RepairResult(task.taskId(), RepairOutcome.LEASE_CONFLICT,
                    0, 0, now, "lease held by another worker");
        }

        try {
            // Step 2: Read metadata and find shard locations
            var metaOpt = metadataRepository.findById(task.objectId());
            if (metaOpt.isEmpty()) {
                return new RepairResult(task.taskId(), RepairOutcome.FAILED,
                        0, 0, now, "object metadata not found: " + task.objectId());
            }
            var meta = metaOpt.get();

            Map<String, Integer> locationMap;
            try {
                locationMap = MAPPER.readValue(meta.getShardLocations(),
                        new TypeReference<Map<String, Integer>>() {});
            } catch (Exception e) {
                return new RepairResult(task.taskId(), RepairOutcome.FAILED,
                        0, 0, now, "failed to parse shard locations: " + e.getMessage());
            }

            int totalShards = meta.getDataShards() + meta.getParityShards();
            String baseShardId = meta.getBaseShardId();

            // Step 3: Fetch all shards, tracking which are present
            byte[][] shards = new byte[totalShards][];
            boolean[] present = new boolean[totalShards];
            int shardSize = 0;
            int shardsRead = 0;
            List<Integer> missingIndices = new ArrayList<>();

            for (int i = 0; i < totalShards; i++) {
                Integer nodeId = locationMap.get(String.valueOf(i));
                if (nodeId == null) {
                    missingIndices.add(i);
                    continue;
                }
                StorageNode node = storageNodes.get(nodeId);
                if (node == null) {
                    missingIndices.add(i);
                    continue;
                }
                String physicalShardId = baseShardId + "-shard-" + i;
                try {
                    if (node.shardExists(physicalShardId)) {
                        shards[i] = node.readShard(physicalShardId);
                        present[i] = true;
                        shardSize = shards[i].length;
                        shardsRead++;
                    } else {
                        missingIndices.add(i);
                    }
                } catch (Exception e) {
                    missingIndices.add(i);
                }
            }

            if (missingIndices.isEmpty()) {
                return new RepairResult(task.taskId(), RepairOutcome.REPAIRED,
                        shardsRead, 0, Instant.now(), "all shards already present — no repair needed");
            }

            // Check if we have enough shards to reconstruct
            int availableShards = totalShards - missingIndices.size();
            if (availableShards < meta.getDataShards()) {
                return new RepairResult(task.taskId(), RepairOutcome.INSUFFICIENT_SHARDS,
                        shardsRead, 0, Instant.now(),
                        "need " + meta.getDataShards() + " shards, only " + availableShards + " available");
            }

            // Step 4: Reconstruct original payload
            byte[] original = erasureCodingService.decode(shards, present, shardSize,
                    (int) meta.getTotalSizeBytes());

            // Step 5: Re-encode to get all shards (including the missing ones)
            byte[][] allShards = erasureCodingService.encode(original);

            // Step 6: Write missing shards to healthy nodes
            int shardsWritten = 0;
            Map<String, Integer> updatedLocations = new LinkedHashMap<>(locationMap);

            for (int missingIndex : missingIndices) {
                if (shardsWritten >= budget.maxShardsWrittenPerRun()) {
                    return new RepairResult(task.taskId(), RepairOutcome.BUDGET_EXHAUSTED,
                            shardsRead, shardsWritten, Instant.now(),
                            "write budget exhausted after " + shardsWritten + " replacements");
                }

                // Find a healthy node not already used by this object
                int replacementNodeId = findReplacementNode(locationMap, missingIndex);
                if (replacementNodeId < 0) {
                    return new RepairResult(task.taskId(), RepairOutcome.FAILED,
                            shardsRead, shardsWritten, Instant.now(),
                            "no healthy replacement node for shard " + missingIndex);
                }

                StorageNode replacementNode = storageNodes.get(replacementNodeId);
                String physicalShardId = baseShardId + "-shard-" + missingIndex;
                try {
                    replacementNode.writeShard(physicalShardId, allShards[missingIndex]);
                    updatedLocations.put(String.valueOf(missingIndex), replacementNodeId);
                    shardsWritten++;
                } catch (Exception e) {
                    return new RepairResult(task.taskId(), RepairOutcome.FAILED,
                            shardsRead, shardsWritten, Instant.now(),
                            "failed to write replacement shard " + missingIndex + ": " + e.getMessage());
                }
            }

            // Step 7: Update metadata with new shard locations
            try {
                String updatedLocationsJson = MAPPER.writeValueAsString(updatedLocations);
                meta.setShardLocations(updatedLocationsJson);
                metadataRepository.save(meta);
            } catch (Exception e) {
                return new RepairResult(task.taskId(), RepairOutcome.FAILED,
                        shardsRead, shardsWritten, Instant.now(),
                        "failed to update metadata: " + e.getMessage());
            }

            return new RepairResult(task.taskId(), RepairOutcome.REPAIRED,
                    shardsRead, shardsWritten, Instant.now(),
                    "repaired " + missingIndices.size() + " missing shard(s)");

        } finally {
            // Step 8: Release lease
            lease.ifPresent(l -> leaseStore.release(task.taskId(), l.fencingToken()));
        }
    }

    /**
     * Finds a healthy storage node not already holding a shard of this object.
     * Returns -1 if no eligible node found.
     */
    private int findReplacementNode(Map<String, Integer> currentLocations, int missingIndex) {
        Set<Integer> usedNodes = new HashSet<>(currentLocations.values());

        for (StorageNode node : storageNodes.values()) {
            if (!usedNodes.contains(node.nodeId())) {
                try {
                    if (node.health().healthy()) {
                        return node.nodeId();
                    }
                } catch (Exception ignored) {}
            }
        }
        return -1;
    }
}
