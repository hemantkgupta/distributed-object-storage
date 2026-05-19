package com.systemdesign.objectstorage.gc;

import com.systemdesign.objectstorage.model.GCResult;
import com.systemdesign.objectstorage.multipart.MultipartUpload;
import com.systemdesign.objectstorage.multipart.UploadPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abandoned-multipart-upload cleanup: INITIATED uploads older than TTL are aborted.
 */
class OrphanAndLifecycleGCAbandonedUploadsTest {

    private GCTestSupport.FakeUploadRepo uploadRepo;
    private GCTestSupport.FakePartRepo partRepo;
    private OrphanAndLifecycleGC gc;

    @BeforeEach
    void setUp() {
        uploadRepo = new GCTestSupport.FakeUploadRepo();
        partRepo = new GCTestSupport.FakePartRepo();
        Map<Integer, com.systemdesign.objectstorage.storage.StorageNode> nodes = new LinkedHashMap<>();
        nodes.put(1, new GCTestSupport.InMemoryNode(1));

        gc = new OrphanAndLifecycleGC(nodes, new GCTestSupport.FakeMetadataRepo(),
                uploadRepo, partRepo);
    }

    @Test
    void initiatedUploadOlderThanTtlIsAborted() {
        MultipartUpload upload = MultipartUpload.builder()
                .bucket("b").objectKey("k")
                .status(MultipartUpload.UploadStatus.INITIATED)
                .build();
        upload.setCreatedAt(Instant.now().minus(Duration.ofDays(30)));
        uploadRepo.save(upload);

        GCResult result = gc.collect(new GCPolicy(Duration.ofHours(1), Duration.ofDays(7)));

        assertThat(result.abandonedUploadsAborted()).isEqualTo(1);
        assertThat(uploadRepo.findById(upload.getId()).orElseThrow().getStatus())
                .isEqualTo(MultipartUpload.UploadStatus.ABORTED);
    }

    @Test
    void recentInitiatedUploadIsNotAborted() {
        MultipartUpload upload = MultipartUpload.builder()
                .bucket("b").objectKey("k")
                .status(MultipartUpload.UploadStatus.INITIATED)
                .build();
        upload.setCreatedAt(Instant.now().minus(Duration.ofMinutes(5)));
        uploadRepo.save(upload);

        GCResult result = gc.collect(new GCPolicy(Duration.ofHours(1), Duration.ofDays(7)));

        assertThat(result.abandonedUploadsAborted()).isZero();
        assertThat(uploadRepo.findById(upload.getId()).orElseThrow().getStatus())
                .isEqualTo(MultipartUpload.UploadStatus.INITIATED);
    }

    @Test
    void completedUploadIsNotTouched() {
        MultipartUpload upload = MultipartUpload.builder()
                .bucket("b").objectKey("k")
                .status(MultipartUpload.UploadStatus.COMPLETED)
                .build();
        upload.setCreatedAt(Instant.now().minus(Duration.ofDays(30)));
        uploadRepo.save(upload);

        GCResult result = gc.collect(new GCPolicy(Duration.ofHours(1), Duration.ofDays(7)));

        assertThat(result.abandonedUploadsAborted()).isZero();
    }

    @Test
    void partsAreDeletedWhenUploadIsAborted() {
        MultipartUpload upload = MultipartUpload.builder()
                .bucket("b").objectKey("k")
                .status(MultipartUpload.UploadStatus.INITIATED)
                .build();
        upload.setCreatedAt(Instant.now().minus(Duration.ofDays(30)));
        uploadRepo.save(upload);

        // Save 3 parts
        for (int n = 1; n <= 3; n++) {
            UploadPart part = UploadPart.builder()
                    .uploadId(upload.getId())
                    .partNumber(n)
                    .etag("e" + n)
                    .baseShardId("base-" + n)
                    .shardLocations("{}")
                    .sizeBytes(100)
                    .build();
            partRepo.save(part);
        }
        assertThat(partRepo.findByUploadIdOrderByPartNumberAsc(upload.getId())).hasSize(3);

        gc.collect(new GCPolicy(Duration.ofHours(1), Duration.ofDays(7)));

        assertThat(partRepo.findByUploadIdOrderByPartNumberAsc(upload.getId())).isEmpty();
    }
}
