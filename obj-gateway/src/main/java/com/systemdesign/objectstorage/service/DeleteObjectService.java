package com.systemdesign.objectstorage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.objectstorage.model.ObjectMetadata;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * DELETE object service: metadata lookup → delete shards from all storage nodes →
 * delete metadata record.
 *
 * <p>Shard deletion is best-effort: if a storage node is temporarily unreachable,
 * the shard remains as an orphan until the GC service cleans it.
 */
@Service
public class DeleteObjectService {

    @Autowired
    private NodeStorageService nodeStorageService;

    @Autowired
    private MetadataRepository metadataRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Deletes an object and all its shards.
     *
     * @return true if the object was found and deleted, false if not found
     */
    @Transactional
    public boolean deleteObject(String bucket, String objectKey) throws Exception {
        Optional<ObjectMetadata> metaOpt = metadataRepository.findByBucketAndObjectKey(bucket, objectKey);
        if (metaOpt.isEmpty()) {
            return false;
        }
        ObjectMetadata meta = metaOpt.get();

        Map<String, Integer> locationMap =
                MAPPER.readValue(meta.getShardLocations(), new TypeReference<Map<String, Integer>>() {});

        String baseShardId = meta.getBaseShardId();
        int totalShards = meta.getDataShards() + meta.getParityShards();

        // Best-effort shard deletion
        for (int i = 0; i < totalShards; i++) {
            Integer nodeId = locationMap.get(String.valueOf(i));
            if (nodeId == null) continue;
            String physicalShardId = baseShardId + "-shard-" + i;
            try {
                nodeStorageService.deleteShard(nodeId, physicalShardId);
            } catch (Exception e) {
                // Log but continue — orphaned shards are cleaned by GC
                System.err.println("Could not delete shard " + physicalShardId
                        + " on node " + nodeId + ": " + e.getMessage());
            }
        }

        metadataRepository.delete(meta);
        return true;
    }
}
