package com.systemdesign.objectstorage.multipart;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Multipart Upload API — mirrors the S3 multipart contract.
 *
 * Flow:
 *   1. POST /{bucket}/{key}?uploads
 *        → Initiate. Returns {"uploadId":"<uuid>"}
 *
 *   2. PUT  /{bucket}/{key}?partNumber=N&uploadId=X  (multipart/form-data, field "part")
 *        → Upload part N. Erasure-codes the chunk, writes shards, returns ETag header.
 *        Repeat for every chunk, in any order, concurrently.
 *
 *   3. POST /{bucket}/{key}?uploadId=X&complete
 *        Body: JSON array of {"partNumber":N,"etag":"..."} in ascending order.
 *        → Verifies all ETags, assembles a single ObjectMetadata row, marks upload COMPLETED.
 *
 *   4. DELETE /{bucket}/{key}?uploadId=X
 *        → Aborts: deletes all part shards and the multipart_upload row.
 *
 * Why parts keep their own shardLocations:
 *   Each 100 MB chunk is independently erasure-coded into 6 shards at upload time.
 *   On GET the assembly reads parts in partNumber order and decodes each independently.
 *   This avoids re-encoding the entire concatenated object on complete, which would
 *   require the entire payload to be in memory simultaneously — exactly the problem
 *   multipart upload was designed to avoid.
 */
@RestController
@RequestMapping("/api/v1/storage")
public class MultipartUploadController {

    @Autowired
    private MultipartUploadRepository multipartUploadRepository;

    @Autowired
    private UploadPartRepository uploadPartRepository;

    @Autowired
    private MetadataRepository metadataRepository;

    @Autowired
    private ErasureCodingService erasureCodingService;

    @Autowired
    private NodeStorageService nodeStorageService;

    @Autowired
    private ConsistentHashRing hashRing;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1: Initiate
    // POST /api/v1/storage/{bucket}/{objectKey}?uploads
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping(value = "/{bucket}/{objectKey}", params = "uploads")
    @Transactional
    public ResponseEntity<Map<String, String>> initiateMultipartUpload(
            @PathVariable String bucket,
            @PathVariable String objectKey) {

        MultipartUpload upload = MultipartUpload.builder()
                .bucket(bucket)
                .objectKey(objectKey)
                .status(MultipartUpload.UploadStatus.INITIATED)
                .build();
        multipartUploadRepository.save(upload);

        return ResponseEntity.ok(Map.of("uploadId", upload.getId()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2: Upload part
    // PUT /api/v1/storage/{bucket}/{objectKey}?partNumber=N&uploadId=X
    // ─────────────────────────────────────────────────────────────────────────
    @PutMapping(value = "/{bucket}/{objectKey}", params = {"partNumber", "uploadId"})
    @Transactional
    public ResponseEntity<String> uploadPart(
            @PathVariable String bucket,
            @PathVariable String objectKey,
            @RequestParam int partNumber,
            @RequestParam String uploadId,
            @RequestParam("part") MultipartFile partFile) {

        // Verify upload exists and is still in progress
        MultipartUpload upload = multipartUploadRepository
                .findByIdAndStatus(uploadId, MultipartUpload.UploadStatus.INITIATED)
                .orElse(null);
        if (upload == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Upload not found or already completed/aborted: " + uploadId);
        }
        if (!upload.getBucket().equals(bucket) || !upload.getObjectKey().equals(objectKey)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("bucket/objectKey mismatch for uploadId " + uploadId);
        }

        try {
            byte[] partBytes = partFile.getBytes();

            // ETag = Base64 MD5 of the raw part bytes
            MessageDigest md = MessageDigest.getInstance("MD5");
            String etag = Base64.getEncoder().encodeToString(md.digest(partBytes));

            // Erasure-code the part
            byte[][] shards = erasureCodingService.encode(partBytes);

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

            // Upsert the part row (idempotent retry of same partNumber is safe)
            UploadPart existingPart = uploadPartRepository
                    .findByUploadIdAndPartNumber(uploadId, partNumber)
                    .orElse(null);
            if (existingPart != null) {
                // Clean up the old shards before overwriting
                deletePartShards(existingPart);
                uploadPartRepository.delete(existingPart);
            }

            UploadPart part = UploadPart.builder()
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .sizeBytes(partBytes.length)
                    .etag(etag)
                    .baseShardId(baseShardId)
                    .shardLocations(locationMapJson.toString())
                    .build();
            uploadPartRepository.save(part);

            return ResponseEntity.ok()
                    .header("ETag", etag)
                    .body("Part " + partNumber + " stored. ETag: " + etag);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to store part " + partNumber + ": " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 3: Complete
    // POST /api/v1/storage/{bucket}/{objectKey}?uploadId=X&complete
    // Body: [{"partNumber":1,"etag":"..."},{"partNumber":2,"etag":"..."},...]
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping(value = "/{bucket}/{objectKey}", params = {"uploadId", "complete"})
    @Transactional
    public ResponseEntity<String> completeMultipartUpload(
            @PathVariable String bucket,
            @PathVariable String objectKey,
            @RequestParam String uploadId,
            @RequestBody List<Map<String, Object>> partManifest) {

        MultipartUpload upload = multipartUploadRepository
                .findByIdAndStatus(uploadId, MultipartUpload.UploadStatus.INITIATED)
                .orElse(null);
        if (upload == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Upload not found or not INITIATED: " + uploadId);
        }

        try {
            // Validate every part in the manifest against what's stored
            List<UploadPart> storedParts =
                    uploadPartRepository.findByUploadIdOrderByPartNumberAsc(uploadId);

            if (storedParts.size() != partManifest.size()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Manifest has " + partManifest.size() + " parts but "
                                + storedParts.size() + " were uploaded");
            }

            long totalSize = 0;
            StringBuilder compositeLocations = new StringBuilder("[");

            for (int idx = 0; idx < storedParts.size(); idx++) {
                UploadPart stored = storedParts.get(idx);
                Map<String, Object> manifest = partManifest.get(idx);

                int manifestPartNum = (Integer) manifest.get("partNumber");
                String manifestEtag = (String) manifest.get("etag");

                if (stored.getPartNumber() != manifestPartNum) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body("Part number mismatch: expected " + stored.getPartNumber()
                                    + " got " + manifestPartNum);
                }
                if (!stored.getEtag().equals(manifestEtag)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body("ETag mismatch for part " + manifestPartNum
                                    + ": stored=" + stored.getEtag()
                                    + " provided=" + manifestEtag);
                }

                totalSize += stored.getSizeBytes();
                // Embed each part's shard map as an element in the composite locations array
                compositeLocations.append("{\"baseShardId\":\"").append(stored.getBaseShardId())
                        .append("\",\"shardLocations\":").append(stored.getShardLocations())
                        .append("}");
                if (idx < storedParts.size() - 1) compositeLocations.append(",");
            }
            compositeLocations.append("]");

            // Assemble a single ObjectMetadata row representing the whole object.
            // shardLocations = JSON array of per-part shard maps (one element per part).
            // The combined checksum is computed over the concatenated part ETags (S3 convention).
            String combinedEtag = computeMultipartEtag(storedParts);

            ObjectMetadata metadata = ObjectMetadata.builder()
                    .bucket(bucket)
                    .objectKey(objectKey)
                    .totalSizeBytes(totalSize)
                    .checksum(combinedEtag)
                    .dataShards(ErasureCodingService.DATA_SHARDS)
                    .parityShards(ErasureCodingService.PARITY_SHARDS)
                    .baseShardId("multipart-" + uploadId) // sentinel — actual base IDs are per-part
                    .shardLocations(compositeLocations.toString())
                    .build();
            metadataRepository.save(metadata);

            // Mark upload COMPLETED (parts kept in DB for audit; a background job may purge them)
            upload.setStatus(MultipartUpload.UploadStatus.COMPLETED);
            multipartUploadRepository.save(upload);

            return ResponseEntity.ok("Multipart upload completed: " + bucket + "/" + objectKey
                    + " (" + storedParts.size() + " parts, " + totalSize + " bytes)");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to complete upload: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 4: Abort
    // DELETE /api/v1/storage/{bucket}/{objectKey}?uploadId=X
    // ─────────────────────────────────────────────────────────────────────────
    @DeleteMapping(value = "/{bucket}/{objectKey}", params = "uploadId")
    @Transactional
    public ResponseEntity<String> abortMultipartUpload(
            @PathVariable String bucket,
            @PathVariable String objectKey,
            @RequestParam String uploadId) {

        MultipartUpload upload = multipartUploadRepository
                .findByIdAndStatus(uploadId, MultipartUpload.UploadStatus.INITIATED)
                .orElse(null);
        if (upload == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Upload not found or not INITIATED: " + uploadId);
        }

        // Delete all stored shards for every part
        List<UploadPart> parts = uploadPartRepository.findByUploadIdOrderByPartNumberAsc(uploadId);
        for (UploadPart part : parts) {
            deletePartShards(part);
        }

        // Cascade DELETE on upload_part due to ON DELETE CASCADE on FK
        upload.setStatus(MultipartUpload.UploadStatus.ABORTED);
        multipartUploadRepository.save(upload);
        uploadPartRepository.deleteAll(parts);

        return ResponseEntity.ok("Aborted multipart upload: " + uploadId
                + " (" + parts.size() + " parts cleaned up)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Best-effort shard cleanup for a single part. */
    private void deletePartShards(UploadPart part) {
        try {
            Map<String, Integer> locationMap =
                    MAPPER.readValue(part.getShardLocations(), new TypeReference<Map<String, Integer>>() {});
            int totalShards = ErasureCodingService.DATA_SHARDS + ErasureCodingService.PARITY_SHARDS;
            for (int i = 0; i < totalShards; i++) {
                Integer nodeId = locationMap.get(String.valueOf(i));
                if (nodeId == null) continue;
                String physicalShardId = part.getBaseShardId() + "-shard-" + i;
                try {
                    nodeStorageService.deleteShard(nodeId, physicalShardId);
                } catch (Exception e) {
                    System.err.println("Could not delete shard " + physicalShardId + " on node " + nodeId);
                }
            }
        } catch (Exception e) {
            System.err.println("Could not parse shardLocations for part " + part.getId() + ": " + e.getMessage());
        }
    }

    /**
     * S3-compatible multipart ETag: MD5 of the concatenated binary MD5 digests of all parts.
     * Result is base64-encoded (we diverge from S3's hex format to stay consistent with
     * the single-object checksum field).
     */
    private String computeMultipartEtag(List<UploadPart> parts) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        for (UploadPart part : parts) {
            // Each stored ETag is Base64(MD5(partBytes)) — decode back to raw 16-byte digest
            byte[] partDigest = Base64.getDecoder().decode(part.getEtag());
            md.update(partDigest);
        }
        return Base64.getEncoder().encodeToString(md.digest()) + "-" + parts.size();
    }
}
