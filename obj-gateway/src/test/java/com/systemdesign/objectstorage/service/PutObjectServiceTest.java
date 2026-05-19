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
 * Tests for the storage-first / metadata-second ordering of {@link PutObjectService}.
 * If metadata save throws AFTER shards have been written, the shards remain on
 * the storage nodes as orphans (cleaned up later by the GC sweep) — the system
 * may not have persisted a metadata row referencing missing shards.
 */
class PutObjectServiceTest {

    private PutObjectService put;
    private InMemoryNodeStorageService storage;
    private InMemoryMetadataRepository metadata;

    @BeforeEach
    void setUp() throws Exception {
        put = new PutObjectService();
        storage = new InMemoryNodeStorageService();
        metadata = new InMemoryMetadataRepository();
        ConsistentHashRing ring = new ConsistentHashRing();
        for (int i = 1; i <= 6; i++) ring.addNode(i);

        inject(put, "erasureCodingService", new ErasureCodingService());
        inject(put, "nodeStorageService", storage);
        inject(put, "metadataRepository", metadata);
        inject(put, "hashRing", ring);
    }

    @Test
    void putWritesSixShardsAndPersistsMetadata() throws Exception {
        byte[] payload = "happy path payload".getBytes(StandardCharsets.UTF_8);
        put.putObject("photos", "cat.jpg", payload);

        assertThat(storage.storedShardCount())
                .as("k=4 data + m=2 parity = 6 shards stored")
                .isEqualTo(6);
        assertThat(metadata.findByBucketAndObjectKey("photos", "cat.jpg")).isPresent();
    }

    @Test
    void metadataReferencesTheBaseShardIdUsedToWriteShards() throws Exception {
        put.putObject("b", "k", "x".repeat(64).getBytes(StandardCharsets.UTF_8));
        var meta = metadata.findByBucketAndObjectKey("b", "k").orElseThrow();
        // Every stored shard begins with the base shard id (UUID-shard-N).
        for (String key : storage.snapshot().keySet()) {
            String shardName = key.substring(key.indexOf(':') + 1);
            assertThat(shardName).startsWith(meta.getBaseShardId());
        }
    }

    @Test
    void metadataSaveFailureLeavesShardsAsOrphansNotMetadata() {
        // Force the metadata save to throw AFTER shards are written.
        metadata.failNextSave = true;
        byte[] payload = "will-orphan".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> put.putObject("b2", "k2", payload))
                .isInstanceOf(Exception.class);

        // The shards are still on the storage nodes — six orphan files.
        assertThat(storage.storedShardCount()).isEqualTo(6);
        // No metadata row was persisted.
        assertThat(metadata.findByBucketAndObjectKey("b2", "k2")).isEmpty();
    }

    @Test
    void checksumStoredInMetadataIsBase64MD5OfPayload() throws Exception {
        byte[] payload = "verifiable".getBytes(StandardCharsets.UTF_8);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        String expected = java.util.Base64.getEncoder().encodeToString(md.digest(payload));

        put.putObject("b", "k-cs", payload);

        var meta = metadata.findByBucketAndObjectKey("b", "k-cs").orElseThrow();
        assertThat(meta.getChecksum()).isEqualTo(expected);
    }

    @Test
    void shardCountInMetadataMatchesErasureConfiguration() throws Exception {
        put.putObject("b", "k-shape", "hello".getBytes(StandardCharsets.UTF_8));
        var meta = metadata.findByBucketAndObjectKey("b", "k-shape").orElseThrow();
        assertThat(meta.getDataShards()).isEqualTo(ErasureCodingService.DATA_SHARDS);
        assertThat(meta.getParityShards()).isEqualTo(ErasureCodingService.PARITY_SHARDS);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
