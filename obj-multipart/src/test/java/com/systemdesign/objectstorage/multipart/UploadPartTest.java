package com.systemdesign.objectstorage.multipart;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link UploadPart} entity: builder, field defaults,
 * timestamp lifecycle.
 */
class UploadPartTest {

    @Test
    void builderPopulatesAllFields() {
        UploadPart part = UploadPart.builder()
                .uploadId("upload-1")
                .partNumber(3)
                .sizeBytes(100 * 1024 * 1024)
                .etag("MD5BASE64==")
                .baseShardId("base-3")
                .shardLocations("{\"0\":1,\"1\":2}")
                .build();

        assertThat(part.getUploadId()).isEqualTo("upload-1");
        assertThat(part.getPartNumber()).isEqualTo(3);
        assertThat(part.getSizeBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(part.getEtag()).isEqualTo("MD5BASE64==");
        assertThat(part.getBaseShardId()).isEqualTo("base-3");
        assertThat(part.getShardLocations()).isEqualTo("{\"0\":1,\"1\":2}");
    }

    @Test
    void noArgsConstructorYieldsDefaults() {
        UploadPart part = new UploadPart();
        assertThat(part.getPartNumber()).isZero();
        assertThat(part.getSizeBytes()).isZero();
        assertThat(part.getEtag()).isNull();
    }

    @Test
    void partNumberFollowsS3OneIndexedConvention() {
        // Part numbers in the API are 1-indexed (S3 compatibility). Builder accepts 1.
        UploadPart part = UploadPart.builder().partNumber(1).build();
        assertThat(part.getPartNumber()).isOne();
    }

    @Test
    void prePersistHookSetsCreatedAt() throws Exception {
        UploadPart part = UploadPart.builder().partNumber(1).build();
        assertThat(part.getCreatedAt()).isNull();

        var m = UploadPart.class.getDeclaredMethod("onCreate");
        m.setAccessible(true);
        m.invoke(part);

        assertThat(part.getCreatedAt()).isNotNull();
    }

    @Test
    void equalsRespectsAllFields() {
        UploadPart a = UploadPart.builder().uploadId("u").partNumber(1).etag("e").build();
        UploadPart b = UploadPart.builder().uploadId("u").partNumber(1).etag("e").build();
        UploadPart c = UploadPart.builder().uploadId("u").partNumber(2).etag("e").build();

        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }
}
