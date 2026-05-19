package com.systemdesign.objectstorage.controller;

import com.systemdesign.objectstorage.service.DeleteObjectService;
import com.systemdesign.objectstorage.service.GetObjectService;
import com.systemdesign.objectstorage.service.PutObjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Thin HTTP adapter that delegates to service classes.
 *
 * <p>The controller owns no business logic — it translates HTTP requests into
 * service calls and service results into HTTP responses. All erasure coding,
 * placement, checksum, and metadata logic lives in the service layer.
 */
@RestController
@RequestMapping("/api/v1/storage")
public class StorageGatewayController {

    @Autowired
    private PutObjectService putObjectService;

    @Autowired
    private GetObjectService getObjectService;

    @Autowired
    private DeleteObjectService deleteObjectService;

    // ─────────────────────────────────────────────────────────────────────────
    // PUT  /api/v1/storage/{bucket}/{objectKey}
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/{bucket}/{objectKey}")
    public ResponseEntity<String> putObject(@PathVariable String bucket,
                                            @PathVariable String objectKey,
                                            @RequestParam("file") MultipartFile file) {
        try {
            String result = putObjectService.putObject(bucket, objectKey, file.getBytes());
            return ResponseEntity.ok(result);
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
        try {
            GetObjectService.GetResult result = getObjectService.getObject(bucket, objectKey);
            if (result == null) {
                return ResponseEntity.notFound().build();
            }
            if (!result.checksumMatch()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(("Checksum mismatch for " + bucket + "/" + objectKey
                                + " — possible bit rot detected").getBytes());
            }
            return ResponseEntity.ok(result.data());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE  /api/v1/storage/{bucket}/{objectKey}
    // ─────────────────────────────────────────────────────────────────────────
    @DeleteMapping("/{bucket}/{objectKey}")
    public ResponseEntity<String> deleteObject(@PathVariable String bucket,
                                               @PathVariable String objectKey) {
        try {
            boolean deleted = deleteObjectService.deleteObject(bucket, objectKey);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok("Deleted " + bucket + "/" + objectKey);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete object: " + e.getMessage());
        }
    }
}
