package com.systemdesign.objectstorage.service;

import com.systemdesign.objectstorage.erasure.ErasureCodingService;
import com.systemdesign.objectstorage.model.ObjectMetadata;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import com.systemdesign.objectstorage.routing.ConsistentHashRing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.UUID;

/**
 * PUT object service: checksum → erasure encode → consistent hash placement →
 * write shards to storage nodes → persist metadata.
 *
 * <p>Storage-first, metadata-second ordering: shards are written before metadata
 * is persisted. A crash between the two creates orphaned shards (harmless, cleaned
 * by GC) — the reverse would create metadata referencing missing shards (a
 * correctness failure).
 */
@Service
public class PutObjectService {

    @Autowired
    private ErasureCodingService erasureCodingService;

    @Autowired
    private NodeStorageService nodeStorageService;

    @Autowired
    private MetadataRepository metadataRepository;

    @Autowired
    private ConsistentHashRing hashRing;

    @Transactional
    public String putObject(String bucket, String objectKey, byte[] payload) throws Exception {
        long size = payload.length;

        // Step 1: MD5 checksum — verified on every GET to detect bit-rot
        MessageDigest md = MessageDigest.getInstance("MD5");
        String checksum = java.util.Base64.getEncoder().encodeToString(md.digest(payload));

        // Step 2: Erasure-code into k data + m parity shards
        byte[][] shards = erasureCodingService.encode(payload);

        // Step 3: Assign shards to nodes via Consistent Hash Ring, write to storage
        String baseShardId = UUID.randomUUID().toString();
        StringBuilder locationMapJson = new StringBuilder("{");

        for (int i = 0; i < shards.length; i++) {
            String physicalShardId = baseShardId + "-shard-" + i;
            int targetNode = hashRing.getNode(physicalShardId);
            nodeStorageService.writeShard(targetNode, physicalShardId, shards[i]);
            locationMapJson.append("\"").append(i).append("\":").append(targetNode);
            if (i < shards.length - 1) locationMapJson.append(",");
        }
        locationMapJson.append("}");

        // Step 4: Persist metadata — shardLocations stores the authoritative placement map
        ObjectMetadata metadata = ObjectMetadata.builder()
                .bucket(bucket)
                .objectKey(objectKey)
                .baseShardId(baseShardId)
                .totalSizeBytes(size)
                .checksum(checksum)
                .dataShards(ErasureCodingService.DATA_SHARDS)
                .parityShards(ErasureCodingService.PARITY_SHARDS)
                .shardLocations(locationMapJson.toString())
                .build();

        metadataRepository.save(metadata);

        return "Stored " + bucket + "/" + objectKey;
    }
}
