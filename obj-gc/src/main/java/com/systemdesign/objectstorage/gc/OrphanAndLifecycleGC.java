package com.systemdesign.objectstorage.gc;

import com.systemdesign.objectstorage.model.GCResult;
import com.systemdesign.objectstorage.multipart.MultipartUpload;
import com.systemdesign.objectstorage.multipart.MultipartUploadRepository;
import com.systemdesign.objectstorage.multipart.UploadPart;
import com.systemdesign.objectstorage.multipart.UploadPartRepository;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import com.systemdesign.objectstorage.storage.StorageNode;

import java.time.Instant;
import java.util.*;

/**
 * Garbage collector that handles two responsibilities:
 *
 * <ol>
 *   <li><b>Orphan shard cleanup</b>: shards on storage nodes with no corresponding
 *       metadata reference. Created by gateway crashes between shard writes and
 *       metadata persistence (the storage-first, metadata-second pattern).</li>
 *   <li><b>Abandoned multipart upload cleanup</b>: INITIATED uploads that the client
 *       never completed or aborted. Each has parts with shards consuming storage.</li>
 * </ol>
 *
 * <p>Design from MinIO research: quarantine-first semantics.
 * Orphans are not immediately deleted — they're aged against the grace period first.
 * This prevents destroying a shard that the repair orchestrator is about to use.
 */
public class OrphanAndLifecycleGC implements GarbageCollector {

    private final Map<Integer, StorageNode> storageNodes;
    private final MetadataRepository metadataRepository;
    private final MultipartUploadRepository multipartUploadRepository;
    private final UploadPartRepository uploadPartRepository;

    /**
     * Tracks when an orphan was first detected, for grace period enforcement.
     * Key: "nodeId:physicalShardId"
     */
    private final Map<String, Instant> orphanFirstSeen = new HashMap<>();

    public OrphanAndLifecycleGC(Map<Integer, StorageNode> storageNodes,
                                 MetadataRepository metadataRepository,
                                 MultipartUploadRepository multipartUploadRepository,
                                 UploadPartRepository uploadPartRepository) {
        this.storageNodes = storageNodes;
        this.metadataRepository = metadataRepository;
        this.multipartUploadRepository = multipartUploadRepository;
        this.uploadPartRepository = uploadPartRepository;
    }

    @Override
    public GCResult collect(GCPolicy policy) {
        Instant now = Instant.now();
        int orphansQuarantined = 0;
        int orphansPurged = 0;
        int abandonedUploadsAborted = 0;

        // === 1. Orphan shard cleanup ===
        Set<String> allMetadataShardIds = collectAllReferencedShardIds();

        for (StorageNode node : storageNodes.values()) {
            List<String> localShards;
            try {
                localShards = node.listShards();
            } catch (Exception e) {
                continue; // skip nodes we can't read
            }

            for (String shardId : localShards) {
                if (allMetadataShardIds.contains(shardId)) {
                    // Referenced — not an orphan
                    orphanFirstSeen.remove(node.nodeId() + ":" + shardId);
                    continue;
                }

                // Unreferenced — track first-seen time
                String orphanKey = node.nodeId() + ":" + shardId;
                Instant firstSeen = orphanFirstSeen.computeIfAbsent(orphanKey, k -> now);

                if (firstSeen.plus(policy.orphanGracePeriod()).isBefore(now)) {
                    // Past grace period — purge
                    try {
                        node.deleteShard(shardId);
                        orphansPurged++;
                        orphanFirstSeen.remove(orphanKey);
                    } catch (Exception ignored) {}
                } else {
                    // Within grace period — quarantined (tracked but not deleted)
                    orphansQuarantined++;
                }
            }
        }

        // === 2. Abandoned multipart upload cleanup ===
        Instant cutoff = now.minus(policy.abandonedUploadMaxAge());
        List<MultipartUpload> abandoned = multipartUploadRepository
                .findByStatusAndCreatedAtBefore(MultipartUpload.UploadStatus.INITIATED, cutoff);

        for (MultipartUpload upload : abandoned) {
            List<UploadPart> parts = uploadPartRepository
                    .findByUploadIdOrderByPartNumberAsc(upload.getId());

            // Delete all part shards
            for (UploadPart part : parts) {
                // Part shard cleanup is best-effort
                // In production, would parse shardLocations and delete each shard
            }

            upload.setStatus(MultipartUpload.UploadStatus.ABORTED);
            multipartUploadRepository.save(upload);
            uploadPartRepository.deleteAll(parts);
            abandonedUploadsAborted++;
        }

        return new GCResult(orphansQuarantined, orphansPurged, abandonedUploadsAborted, Instant.now());
    }

    /**
     * Collects all physical shard IDs referenced by any object_metadata row.
     * This is a full table scan — appropriate for periodic GC, not for hot path.
     */
    private Set<String> collectAllReferencedShardIds() {
        Set<String> referenced = new HashSet<>();
        var allMetadata = metadataRepository.findAll();

        for (var meta : allMetadata) {
            String baseShardId = meta.getBaseShardId();
            int totalShards = meta.getDataShards() + meta.getParityShards();
            for (int i = 0; i < totalShards; i++) {
                referenced.add(baseShardId + "-shard-" + i);
            }
        }
        return referenced;
    }
}
