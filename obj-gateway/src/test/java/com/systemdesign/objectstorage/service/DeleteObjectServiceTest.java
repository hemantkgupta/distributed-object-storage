package com.systemdesign.objectstorage.service;

import com.systemdesign.objectstorage.erasure.ErasureCodingService;
import com.systemdesign.objectstorage.routing.ConsistentHashRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DELETE: removes all shards and the metadata row. Storage-first
 * ordering matters less here (it's the inverse of PUT) — both orderings produce
 * the same eventually-consistent state. The chosen order is shards then metadata,
 * documented in the service's javadoc.
 */
class DeleteObjectServiceTest {

    private PutObjectService put;
    private DeleteObjectService delete;
    private InMemoryNodeStorageService storage;
    private InMemoryMetadataRepository metadata;

    @BeforeEach
    void setUp() throws Exception {
        put = new PutObjectService();
        delete = new DeleteObjectService();
        storage = new InMemoryNodeStorageService();
        metadata = new InMemoryMetadataRepository();
        ConsistentHashRing ring = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ring.addNode(i);

        ErasureCodingService ec = new ErasureCodingService();
        inject(put, "erasureCodingService", ec);
        inject(put, "nodeStorageService", storage);
        inject(put, "metadataRepository", metadata);
        inject(put, "hashRing", ring);

        inject(delete, "nodeStorageService", storage);
        inject(delete, "metadataRepository", metadata);
    }

    @Test
    void deleteRemovesAllShardsAndMetadata() throws Exception {
        put.putObject("b", "k", "to-delete".getBytes(StandardCharsets.UTF_8));
        assertThat(storage.storedShardCount()).isEqualTo(6);

        boolean deleted = delete.deleteObject("b", "k");

        assertThat(deleted).isTrue();
        assertThat(storage.storedShardCount()).isZero();
        assertThat(metadata.findByBucketAndObjectKey("b", "k")).isEmpty();
    }

    @Test
    void deleteOnMissingObjectReturnsFalse() throws Exception {
        boolean deleted = delete.deleteObject("b", "no-such-key");
        assertThat(deleted).isFalse();
    }

    @Test
    void deleteOnlyAffectsTargetObject() throws Exception {
        put.putObject("b", "keep", "stays".getBytes(StandardCharsets.UTF_8));
        put.putObject("b", "kill", "goes".getBytes(StandardCharsets.UTF_8));
        assertThat(storage.storedShardCount()).isEqualTo(12);

        delete.deleteObject("b", "kill");

        assertThat(storage.storedShardCount()).isEqualTo(6);
        assertThat(metadata.findByBucketAndObjectKey("b", "keep")).isPresent();
        assertThat(metadata.findByBucketAndObjectKey("b", "kill")).isEmpty();
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
