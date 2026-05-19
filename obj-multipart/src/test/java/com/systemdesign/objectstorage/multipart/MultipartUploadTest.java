package com.systemdesign.objectstorage.multipart;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link MultipartUpload} entity: builder, status transitions,
 * timestamp lifecycle hooks.
 */
class MultipartUploadTest {

    @Test
    void builderPopulatesBucketKeyAndStatus() {
        MultipartUpload upload = MultipartUpload.builder()
                .bucket("backups")
                .objectKey("snapshot.tgz")
                .status(MultipartUpload.UploadStatus.INITIATED)
                .build();

        assertThat(upload.getBucket()).isEqualTo("backups");
        assertThat(upload.getObjectKey()).isEqualTo("snapshot.tgz");
        assertThat(upload.getStatus()).isEqualTo(MultipartUpload.UploadStatus.INITIATED);
    }

    @Test
    void statusCanTransitionInitiatedToCompleted() {
        MultipartUpload upload = MultipartUpload.builder()
                .status(MultipartUpload.UploadStatus.INITIATED).build();
        upload.setStatus(MultipartUpload.UploadStatus.COMPLETED);
        assertThat(upload.getStatus()).isEqualTo(MultipartUpload.UploadStatus.COMPLETED);
    }

    @Test
    void statusCanTransitionInitiatedToAborted() {
        MultipartUpload upload = MultipartUpload.builder()
                .status(MultipartUpload.UploadStatus.INITIATED).build();
        upload.setStatus(MultipartUpload.UploadStatus.ABORTED);
        assertThat(upload.getStatus()).isEqualTo(MultipartUpload.UploadStatus.ABORTED);
    }

    @Test
    void uploadStatusEnumExposesAllThreeStates() {
        assertThat(MultipartUpload.UploadStatus.values())
                .containsExactly(
                        MultipartUpload.UploadStatus.INITIATED,
                        MultipartUpload.UploadStatus.COMPLETED,
                        MultipartUpload.UploadStatus.ABORTED);
    }

    @Test
    void prePersistHookSetsCreatedAndUpdatedTimestamps() throws Exception {
        MultipartUpload upload = MultipartUpload.builder()
                .status(MultipartUpload.UploadStatus.INITIATED).build();
        var m = MultipartUpload.class.getDeclaredMethod("onCreate");
        m.setAccessible(true);
        m.invoke(upload);

        assertThat(upload.getCreatedAt()).isNotNull();
        assertThat(upload.getUpdatedAt()).isNotNull();
    }

    @Test
    void preUpdateHookAdvancesUpdatedTimestamp() throws Exception {
        MultipartUpload upload = MultipartUpload.builder()
                .status(MultipartUpload.UploadStatus.INITIATED).build();
        var create = MultipartUpload.class.getDeclaredMethod("onCreate");
        create.setAccessible(true);
        create.invoke(upload);
        java.time.Instant original = upload.getUpdatedAt();

        Thread.sleep(2); // ensure clock advances
        var update = MultipartUpload.class.getDeclaredMethod("onUpdate");
        update.setAccessible(true);
        update.invoke(upload);

        assertThat(upload.getUpdatedAt()).isAfter(original);
    }
}
