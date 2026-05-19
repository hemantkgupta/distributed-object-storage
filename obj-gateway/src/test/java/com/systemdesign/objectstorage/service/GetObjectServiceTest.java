package com.systemdesign.objectstorage.service;

import com.systemdesign.objectstorage.erasure.ErasureCodingService;
import com.systemdesign.objectstorage.routing.ConsistentHashRing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for GET: full-shards happy path, degraded-read reconstruction, and the
 * unrecoverable too-many-missing case. Reconstruction is the load-bearing
 * behaviour — a GET that silently returns wrong bytes is worse than failing.
 */
class GetObjectServiceTest {

    private PutObjectService put;
    private GetObjectService get;
    private InMemoryNodeStorageService storage;
    private InMemoryMetadataRepository metadata;

    @BeforeEach
    void setUp() throws Exception {
        put = new PutObjectService();
        get = new GetObjectService();
        storage = new InMemoryNodeStorageService();
        metadata = new InMemoryMetadataRepository();
        ConsistentHashRing ring = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ring.addNode(i);

        ErasureCodingService ec = new ErasureCodingService();
        inject(put, "erasureCodingService", ec);
        inject(put, "nodeStorageService", storage);
        inject(put, "metadataRepository", metadata);
        inject(put, "hashRing", ring);

        inject(get, "erasureCodingService", ec);
        inject(get, "nodeStorageService", storage);
        inject(get, "metadataRepository", metadata);
    }

    @Test
    void getReturnsOriginalBytesWhenAllShardsPresent() throws Exception {
        byte[] payload = "all shards present and accounted for".getBytes(StandardCharsets.UTF_8);
        put.putObject("b", "k", payload);

        GetObjectService.GetResult result = get.getObject("b", "k");

        assertThat(result.data()).isEqualTo(payload);
        assertThat(result.degraded()).isFalse();
        assertThat(result.checksumMatch()).isTrue();
    }

    @Test
    void getReconstructsBytesWhenOneShardIsMissing() throws Exception {
        byte[] payload = "degraded read 1 missing".getBytes(StandardCharsets.UTF_8);
        put.putObject("b", "k", payload);

        // Drop one shard from a deterministic node.
        var meta = metadata.findByBucketAndObjectKey("b", "k").orElseThrow();
        dropShardForIndex(meta, 0);

        GetObjectService.GetResult result = get.getObject("b", "k");

        assertThat(result.data()).isEqualTo(payload);
        assertThat(result.degraded()).isTrue();
        assertThat(result.checksumMatch()).isTrue();
    }

    @Test
    void getReconstructsBytesWhenTwoShardsAreMissing() throws Exception {
        byte[] payload = "degraded read 2 missing — tolerance boundary".getBytes(StandardCharsets.UTF_8);
        put.putObject("b", "k", payload);

        var meta = metadata.findByBucketAndObjectKey("b", "k").orElseThrow();
        dropShardForIndex(meta, 0);
        dropShardForIndex(meta, 5);

        GetObjectService.GetResult result = get.getObject("b", "k");

        assertThat(result.data()).isEqualTo(payload);
        assertThat(result.degraded()).isTrue();
        assertThat(result.checksumMatch()).isTrue();
    }

    @Test
    void getFailsWhenTooManyShardsAreMissing() throws Exception {
        byte[] payload = "unrecoverable".getBytes(StandardCharsets.UTF_8);
        put.putObject("b", "k", payload);

        var meta = metadata.findByBucketAndObjectKey("b", "k").orElseThrow();
        dropShardForIndex(meta, 0);
        dropShardForIndex(meta, 2);
        dropShardForIndex(meta, 4);

        assertThatThrownBy(() -> get.getObject("b", "k"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getReturnsNullForMissingObject() throws Exception {
        assertThat(get.getObject("b", "no-such-key")).isNull();
    }

    private void dropShardForIndex(com.systemdesign.objectstorage.model.ObjectMetadata meta, int idx)
            throws Exception {
        java.util.Map<String, Integer> locationMap = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(meta.getShardLocations(),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Integer>>() {});
        int nodeId = locationMap.get(String.valueOf(idx));
        storage.dropShard(nodeId, meta.getBaseShardId() + "-shard-" + idx);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
