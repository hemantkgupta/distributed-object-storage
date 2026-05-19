package com.systemdesign.objectstorage.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link ObjectMetadata} JPA entity. Verifies the Lombok
 * builder/getter/setter wiring, default values, and the {@code @PrePersist}
 * timestamp hook. We exercise the entity as a plain Java type — full
 * round-trip persistence is covered by the gateway integration paths and not
 * repeated here (the PostgreSQL-specific schema, including the
 * {@code upload_status} enum, is not portable to an in-memory H2 backing).
 */
class ObjectMetadataTest {

    @Test
    void builderPopulatesAllFields() {
        ObjectMetadata m = ObjectMetadata.builder()
                .bucket("photos")
                .objectKey("cat.jpg")
                .totalSizeBytes(1024)
                .checksum("abc=")
                .dataShards(4)
                .parityShards(2)
                .baseShardId("base-1")
                .shardLocations("{\"0\":1}")
                .build();

        assertThat(m.getBucket()).isEqualTo("photos");
        assertThat(m.getObjectKey()).isEqualTo("cat.jpg");
        assertThat(m.getTotalSizeBytes()).isEqualTo(1024L);
        assertThat(m.getChecksum()).isEqualTo("abc=");
        assertThat(m.getDataShards()).isEqualTo(4);
        assertThat(m.getParityShards()).isEqualTo(2);
        assertThat(m.getBaseShardId()).isEqualTo("base-1");
        assertThat(m.getShardLocations()).isEqualTo("{\"0\":1}");
    }

    @Test
    void noArgsConstructorYieldsBlankEntity() {
        ObjectMetadata m = new ObjectMetadata();
        assertThat(m.getBucket()).isNull();
        assertThat(m.getObjectKey()).isNull();
        assertThat(m.getTotalSizeBytes()).isZero();
    }

    @Test
    void settersUpdateFields() {
        ObjectMetadata m = new ObjectMetadata();
        m.setBucket("logs");
        m.setObjectKey("2024-01-01.log");
        assertThat(m.getBucket()).isEqualTo("logs");
        assertThat(m.getObjectKey()).isEqualTo("2024-01-01.log");
    }

    @Test
    void equalsAndHashCodeReflectAllFields() {
        // Lombok @Data generates equals/hashCode across all fields.
        ObjectMetadata a = ObjectMetadata.builder().bucket("b").objectKey("k").build();
        ObjectMetadata b = ObjectMetadata.builder().bucket("b").objectKey("k").build();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void prePersistHookSetsCreatedAt() throws Exception {
        ObjectMetadata m = ObjectMetadata.builder().bucket("b").objectKey("k").build();
        assertThat(m.getCreatedAt()).isNull();

        // Invoke the protected @PrePersist hook directly — JPA would do this on save().
        var method = ObjectMetadata.class.getDeclaredMethod("onCreate");
        method.setAccessible(true);
        method.invoke(m);

        assertThat(m.getCreatedAt()).isNotNull();
    }
}
