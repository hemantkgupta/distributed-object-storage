package com.systemdesign.objectstorage.multipart;

import com.systemdesign.objectstorage.erasure.ErasureCodingService;
import com.systemdesign.objectstorage.model.ObjectMetadata;
import com.systemdesign.objectstorage.repository.MetadataRepository;
import com.systemdesign.objectstorage.routing.ConsistentHashRing;
import com.systemdesign.objectstorage.service.NodeStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the four-step multipart protocol (initiate → uploadPart → complete →
 * abort) end-to-end against a {@link MultipartUploadController} wired with
 * hand-rolled in-memory repositories. The real {@link ErasureCodingService} and
 * {@link ConsistentHashRing} are used; only the JPA-backed repositories and the
 * filesystem-touching {@link NodeStorageService} are faked.
 */
class MultipartUploadControllerTest {

    private MultipartUploadController controller;
    private FakeMultipartUploadRepository uploadRepo;
    private FakeUploadPartRepository partRepo;
    private FakeMetadataRepository metadataRepo;
    private InMemoryNodeStorageService storage;
    private String runId;
    private Path baseStorageDir;

    @BeforeEach
    void setUp() throws Exception {
        controller = new MultipartUploadController();
        uploadRepo = new FakeMultipartUploadRepository();
        partRepo = new FakeUploadPartRepository();
        metadataRepo = new FakeMetadataRepository();
        storage = new InMemoryNodeStorageService();
        runId = "mp-test-" + UUID.randomUUID();
        baseStorageDir = Paths.get(System.getProperty("user.dir"), "data", "storage-nodes");

        ConsistentHashRing ring = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ring.addNode(i);

        inject(controller, "multipartUploadRepository", uploadRepo);
        inject(controller, "uploadPartRepository", partRepo);
        inject(controller, "metadataRepository", metadataRepo);
        inject(controller, "erasureCodingService", new ErasureCodingService());
        inject(controller, "nodeStorageService", storage);
        inject(controller, "hashRing", ring);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanFilesystem() throws IOException {
        // The real NodeStorageService is replaced by our in-memory fake, but
        // defensive cleanup in case anything spilled.
        if (!Files.exists(baseStorageDir)) return;
        try (Stream<Path> children = Files.list(baseStorageDir)) {
            for (Path nodeDir : children.toList()) {
                if (!nodeDir.getFileName().toString().startsWith("node-")) continue;
                try (Stream<Path> shards = Files.list(nodeDir)) {
                    for (Path shard : shards.toList()) {
                        if (shard.getFileName().toString().contains(runId)) {
                            Files.deleteIfExists(shard);
                        }
                    }
                }
            }
        }
    }

    @Test
    void initiateCreatesUploadInInitiatedStatus() {
        ResponseEntity<Map<String, String>> response =
                controller.initiateMultipartUpload("bucket-a", "key-a");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String uploadId = response.getBody().get("uploadId");
        assertThat(uploadId).isNotBlank();

        MultipartUpload stored = uploadRepo.findById(uploadId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(MultipartUpload.UploadStatus.INITIATED);
    }

    @Test
    void uploadPartStoresPartAndReturnsEtag() {
        String uploadId = initiate("b", "k");
        byte[] data = "part-1-bytes".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.uploadPart(
                "b", "k", 1, uploadId, new FakeMultipartFile(data));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("ETag")).isNotBlank();
        assertThat(partRepo.findByUploadIdOrderByPartNumberAsc(uploadId)).hasSize(1);
    }

    @Test
    void uploadPartRejectsBucketKeyMismatch() {
        String uploadId = initiate("b", "k");
        ResponseEntity<String> response = controller.uploadPart(
                "WRONG", "k", 1, uploadId, new FakeMultipartFile("x".getBytes()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void completeCreatesObjectMetadataAndMarksUploadCompleted() {
        String uploadId = initiate("b", "k");
        String etag1 = uploadPart(uploadId, "b", "k", 1, "first-part-bytes");
        String etag2 = uploadPart(uploadId, "b", "k", 2, "second-part-bytes");

        List<Map<String, Object>> manifest = List.of(
                Map.of("partNumber", 1, "etag", etag1),
                Map.of("partNumber", 2, "etag", etag2));

        ResponseEntity<String> response = controller.completeMultipartUpload(
                "b", "k", uploadId, manifest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(uploadRepo.findById(uploadId).orElseThrow().getStatus())
                .isEqualTo(MultipartUpload.UploadStatus.COMPLETED);
        assertThat(metadataRepo.findByBucketAndObjectKey("b", "k")).isPresent();
    }

    @Test
    void completeRejectsManifestWithMismatchedEtag() {
        String uploadId = initiate("b", "k");
        uploadPart(uploadId, "b", "k", 1, "real-bytes");

        List<Map<String, Object>> badManifest = List.of(
                Map.of("partNumber", 1, "etag", "wrong-etag"));

        ResponseEntity<String> response = controller.completeMultipartUpload(
                "b", "k", uploadId, badManifest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void abortMarksUploadAbortedAndDeletesParts() {
        String uploadId = initiate("b", "k");
        uploadPart(uploadId, "b", "k", 1, "to-be-aborted");

        ResponseEntity<String> response = controller.abortMultipartUpload(
                "b", "k", uploadId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(uploadRepo.findById(uploadId).orElseThrow().getStatus())
                .isEqualTo(MultipartUpload.UploadStatus.ABORTED);
        assertThat(partRepo.findByUploadIdOrderByPartNumberAsc(uploadId)).isEmpty();
    }

    @Test
    void uploadPartRejectsCompletedUpload() {
        String uploadId = initiate("b", "k");
        String etag = uploadPart(uploadId, "b", "k", 1, "ok");
        controller.completeMultipartUpload("b", "k", uploadId,
                List.of(Map.of("partNumber", 1, "etag", etag)));

        ResponseEntity<String> response = controller.uploadPart(
                "b", "k", 2, uploadId, new FakeMultipartFile("late".getBytes()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void completeRejectsManifestSizeMismatch() {
        String uploadId = initiate("b", "k");
        uploadPart(uploadId, "b", "k", 1, "only-one-uploaded");

        List<Map<String, Object>> manifest = List.of(
                Map.of("partNumber", 1, "etag", "x"),
                Map.of("partNumber", 2, "etag", "y"));

        ResponseEntity<String> response = controller.completeMultipartUpload(
                "b", "k", uploadId, manifest);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String initiate(String bucket, String key) {
        return controller.initiateMultipartUpload(bucket, key).getBody().get("uploadId");
    }

    private String uploadPart(String uploadId, String bucket, String key, int n, String content) {
        ResponseEntity<String> r = controller.uploadPart(bucket, key, n, uploadId,
                new FakeMultipartFile(content.getBytes(StandardCharsets.UTF_8)));
        return r.getHeaders().getFirst("ETag");
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ── Fakes ──────────────────────────────────────────────────────────────

    static final class FakeMultipartFile implements MultipartFile {
        private final byte[] bytes;
        FakeMultipartFile(byte[] bytes) { this.bytes = bytes; }
        @Override public String getName() { return "part"; }
        @Override public String getOriginalFilename() { return "part.bin"; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }

    /**
     * Drop-in replacement for {@link NodeStorageService} that holds shards in memory
     * keyed by (nodeId, shardId).
     */
    static final class InMemoryNodeStorageService extends NodeStorageService {
        private final Map<String, byte[]> store = new HashMap<>();
        private String key(int nodeId, String shardId) { return nodeId + ":" + shardId; }
        @Override public void writeShard(int nodeId, String shardId, byte[] data) {
            store.put(key(nodeId, shardId), data.clone());
        }
        @Override public byte[] readShard(int nodeId, String shardId) {
            return store.get(key(nodeId, shardId));
        }
        @Override public void deleteShard(int nodeId, String shardId) {
            store.remove(key(nodeId, shardId));
        }
        @Override public boolean shardExists(int nodeId, String shardId) {
            return store.containsKey(key(nodeId, shardId));
        }
    }

    static final class FakeMultipartUploadRepository extends NoopMultipartUploadRepository {
        private final Map<String, MultipartUpload> rows = new LinkedHashMap<>();
        @Override public MultipartUpload save(MultipartUpload u) {
            if (u.getId() == null) u.setId(UUID.randomUUID().toString());
            if (u.getCreatedAt() == null) u.setCreatedAt(Instant.now());
            u.setUpdatedAt(Instant.now());
            rows.put(u.getId(), u);
            return u;
        }
        @Override public Optional<MultipartUpload> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }
        @Override public Optional<MultipartUpload> findByIdAndStatus(
                String id, MultipartUpload.UploadStatus status) {
            return findById(id).filter(u -> u.getStatus() == status);
        }
        @Override public List<MultipartUpload> findByStatusAndCreatedAtBefore(
                MultipartUpload.UploadStatus status, Instant cutoff) {
            return rows.values().stream()
                    .filter(u -> u.getStatus() == status)
                    .filter(u -> u.getCreatedAt() != null && u.getCreatedAt().isBefore(cutoff))
                    .toList();
        }
    }

    static final class FakeUploadPartRepository extends NoopUploadPartRepository {
        private final Map<String, UploadPart> rows = new LinkedHashMap<>();
        @Override public UploadPart save(UploadPart p) {
            if (p.getId() == null) p.setId(UUID.randomUUID().toString());
            if (p.getCreatedAt() == null) p.setCreatedAt(Instant.now());
            rows.put(p.getId(), p);
            return p;
        }
        @Override public List<UploadPart> findByUploadIdOrderByPartNumberAsc(String uploadId) {
            return rows.values().stream()
                    .filter(p -> p.getUploadId().equals(uploadId))
                    .sorted(Comparator.comparingInt(UploadPart::getPartNumber))
                    .toList();
        }
        @Override public Optional<UploadPart> findByUploadIdAndPartNumber(String uploadId, int n) {
            return rows.values().stream()
                    .filter(p -> p.getUploadId().equals(uploadId) && p.getPartNumber() == n)
                    .findFirst();
        }
        @Override public void delete(UploadPart p) { rows.remove(p.getId()); }
        @Override public void deleteAll(Iterable<? extends UploadPart> entities) {
            entities.forEach(this::delete);
        }
    }

    static final class FakeMetadataRepository extends NoopMetadataRepository {
        private final Map<String, ObjectMetadata> rows = new LinkedHashMap<>();
        @Override public ObjectMetadata save(ObjectMetadata m) {
            if (m.getId() == null) m.setId(UUID.randomUUID().toString());
            rows.put(m.getId(), m);
            return m;
        }
        @Override public Optional<ObjectMetadata> findByBucketAndObjectKey(String b, String k) {
            return rows.values().stream()
                    .filter(r -> r.getBucket().equals(b) && r.getObjectKey().equals(k))
                    .findFirst();
        }
    }
}
