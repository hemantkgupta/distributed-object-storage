package com.systemdesign.objectstorage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.objectstorage.erasure.ErasureCodingService;
import com.systemdesign.objectstorage.model.ObjectMetadata;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * GET object service: metadata lookup → fetch shards from stored locations →
 * erasure decode (with degraded read support) → checksum verification.
 *
 * <p>Fetches all shards (data + parity) and tracks which are present. If shards
 * are missing, the decoder reconstructs from any k surviving shards. The MD5
 * checksum of the reconstructed bytes is verified against the stored value —
 * the only defense against silent bit rot producing wrong output.
 */
@Service
public class GetObjectService {

    @Autowired
    private ErasureCodingService erasureCodingService;

    @Autowired
    private NodeStorageService nodeStorageService;

    @Autowired
    private MetadataRepository metadataRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Result of a GET operation.
     */
    public record GetResult(byte[] data, boolean degraded, boolean checksumMatch) {}

    /**
     * Retrieves and reconstructs an object from its erasure-coded shards.
     *
     * @return GetResult with the reconstructed bytes, or null if the object doesn't exist
     */
    public GetResult getObject(String bucket, String objectKey) throws Exception {
        Optional<ObjectMetadata> metaOpt = metadataRepository.findByBucketAndObjectKey(bucket, objectKey);
        if (metaOpt.isEmpty()) {
            return null;
        }
        ObjectMetadata meta = metaOpt.get();

        int totalShards = meta.getDataShards() + meta.getParityShards();
        byte[][] shards = new byte[totalShards][];
        boolean[] present = new boolean[totalShards];

        Map<String, Integer> locationMap =
                MAPPER.readValue(meta.getShardLocations(), new TypeReference<Map<String, Integer>>() {});

        int shardSize = 0;
        String baseShardId = meta.getBaseShardId();
        int missingCount = 0;

        for (int i = 0; i < totalShards; i++) {
            Integer nodeId = locationMap.get(String.valueOf(i));
            if (nodeId == null) {
                missingCount++;
                continue;
            }
            String physicalShardId = baseShardId + "-shard-" + i;
            try {
                if (nodeStorageService.shardExists(nodeId, physicalShardId)) {
                    shards[i] = nodeStorageService.readShard(nodeId, physicalShardId);
                    present[i] = true;
                    shardSize = shards[i].length;
                } else {
                    missingCount++;
                }
            } catch (Exception e) {
                missingCount++;
            }
        }

        // Reconstruct original bytes via erasure decode
        byte[] original = erasureCodingService.decode(shards, present, shardSize, (int) meta.getTotalSizeBytes());

        // Checksum verification — the bit-rot firewall
        MessageDigest md = MessageDigest.getInstance("MD5");
        String actualChecksum = Base64.getEncoder().encodeToString(md.digest(original));
        boolean checksumMatch = actualChecksum.equals(meta.getChecksum());

        return new GetResult(original, missingCount > 0, checksumMatch);
    }
}
