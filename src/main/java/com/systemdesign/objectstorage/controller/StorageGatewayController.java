package com.systemdesign.objectstorage.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.objectstorage.erasure.ErasureCodingService;
import com.systemdesign.objectstorage.model.ObjectMetadata;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import com.systemdesign.objectstorage.routing.ConsistentHashRing;
import com.systemdesign.objectstorage.service.NodeStorageService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/storage")
public class StorageGatewayController {

    @Autowired
    private ErasureCodingService erasureCodingService;

    @Autowired
    private NodeStorageService nodeStorageService;

    @Autowired
    private MetadataRepository metadataRepository;

    @Autowired
    private ConsistentHashRing hashRing;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────────
    // PUT  /api/v1/storage/{bucket}/{objectKey}
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{bucket}/{objectKey}")
    @Transactional
    public ResponseEntity<String> putObject(@PathVariable String bucket,
                                            @PathVariable String objectKey,
                                            @RequestParam("file") MultipartFile file) {
        try {
            byte[] payload = file.getBytes();
            long size = payload.length;

            // MD5 checksum — verified on every GET to detect bit-rot
            MessageDigest md = MessageDigest.getInstance("MD5");
            String checksum = Base64.getEncoder().encodeToString(md.digest(payload));

            // Erasure-code into k data + m parity shards
            byte[][] shards = erasureCodingService.encode(payload);

            // Assign shards to nodes via Consistent Hashing Ring.
            // Prototype: static i+1 until ConsistentHashRing is wired in (Phase 3).
            String baseShardId = UUID.randomUUID().toString();
            // locationMap: shardIndex -> nodeId  e.g. {"0":1,"1":2,...,"5":6}
            StringBuilder locationMapJson = new StringBuilder("{");

            for (int i = 0; i < shards.length; i++) {
                String physicalShardId = baseShardId + "-shard-" + i;
                // Consistent hashing: hash the shardId → walk clockwise → first virtual node → physical nodeId
                int targetNode = hashRing.getNode(physicalShardId);
                nodeStorageService.writeShard(targetNode, physicalShardId, shards[i]);
                locationMapJson.append("\"").append(i).append("\":").append(targetNode);
                if (i < shards.length - 1) locationMapJson.append(",");
            }
            locationMapJson.append("}");

            // Persist metadata — shardLocations stores the JSON map, not just the base ID
            ObjectMetadata metadata = ObjectMetadata.builder()
                    .bucket(bucket)
                    .objectKey(objectKey)
                    .baseShardId(baseShardId)
                    .totalSizeBytes(size)
                    .checksum(checksum)
                    .dataShards(ErasureCodingService.DATA_SHARDS)
                    .parityShards(ErasureCodingService.PARITY_SHARDS)
                    .shardLocations(locationMapJson.toString()) // ← the actual JSON map
                    .build();

            metadataRepository.save(metadata);

            return ResponseEntity.ok("Stored " + bucket + "/" + objectKey);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to store object: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET  /api/v1/storage/{bucket}/{objectKey}
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/{bucket}/{objectKey}")
    public ResponseEntity<?> getObject(@PathVariable String bucket,
                                       @PathVariable String objectKey) {
        Optional<ObjectMetadata> metaOpt = metadataRepository.findByBucketAndObjectKey(bucket, objectKey);
        if (metaOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ObjectMetadata meta = metaOpt.get();

        try {
            int totalShards = meta.getDataShards() + meta.getParityShards();
            byte[][] shards = new byte[totalShards][];
            boolean[] present = new boolean[totalShards];

            // Parse shardLocations JSON → Map<shardIndex, nodeId>
            Map<String, Integer> locationMap =
                    MAPPER.readValue(meta.getShardLocations(), new TypeReference<Map<String, Integer>>() {});

            int shardSize = 0;
            String baseShardId = meta.getBaseShardId();

            for (int i = 0; i < totalShards; i++) {
                Integer nodeId = locationMap.get(String.valueOf(i));
                if (nodeId == null) {
                    System.err.println("No location entry for shard " + i);
                    continue;
                }
                String physicalShardId = baseShardId + "-shard-" + i;
                try {
                    if (nodeStorageService.shardExists(nodeId, physicalShardId)) {
                        shards[i] = nodeStorageService.readShard(nodeId, physicalShardId);
                        present[i] = true;
                        shardSize = shards[i].length;
                    } else {
                        System.err.println("Shard " + physicalShardId + " missing on node " + nodeId);
                    }
                } catch (Exception e) {
                    System.err.println("Shard " + physicalShardId + " unreadable on node " + nodeId + ": " + e.getMessage());
                    present[i] = false;
                }
            }

            // Reconstruct original bytes via erasure decode
            byte[] original = erasureCodingService.decode(shards, present, shardSize, (int) meta.getTotalSizeBytes());

            // ── Checksum verification — detect silent bit-rot ──────────────────
            MessageDigest md = MessageDigest.getInstance("MD5");
            String actualChecksum = Base64.getEncoder().encodeToString(md.digest(original));
            if (!actualChecksum.equals(meta.getChecksum())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(("Checksum mismatch for " + bucket + "/" + objectKey
                                + ": stored=" + meta.getChecksum()
                                + " computed=" + actualChecksum).getBytes());
            }

            return ResponseEntity.ok(original);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE  /api/v1/storage/{bucket}/{objectKey}
    // ─────────────────────────────────────────────────────────────────────────
    @DeleteMapping("/{bucket}/{objectKey}")
    @Transactional
    public ResponseEntity<String> deleteObject(@PathVariable String bucket,
                                               @PathVariable String objectKey) {
        Optional<ObjectMetadata> metaOpt = metadataRepository.findByBucketAndObjectKey(bucket, objectKey);
        if (metaOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ObjectMetadata meta = metaOpt.get();

        try {
            Map<String, Integer> locationMap =
                    MAPPER.readValue(meta.getShardLocations(), new TypeReference<Map<String, Integer>>() {});

            String baseShardId = meta.getBaseShardId();
            int totalShards = meta.getDataShards() + meta.getParityShards();

            for (int i = 0; i < totalShards; i++) {
                Integer nodeId = locationMap.get(String.valueOf(i));
                if (nodeId == null) continue;
                String physicalShardId = baseShardId + "-shard-" + i;
                try {
                    nodeStorageService.deleteShard(nodeId, physicalShardId);
                } catch (Exception e) {
                    // Log but continue — best-effort shard cleanup
                    System.err.println("Could not delete shard " + physicalShardId + " on node " + nodeId + ": " + e.getMessage());
                }
            }

            metadataRepository.delete(meta);
            return ResponseEntity.ok("Deleted " + bucket + "/" + objectKey);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete object: " + e.getMessage());
        }
    }
}
