package com.systemdesign.objectstorage.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the local-filesystem-backed StorageNode primitive: writeShard, readShard,
 * deleteShard, shardExists, listShards, and checksum.
 */
class LocalFilesystemStorageNodeTest {

    @TempDir
    Path tempDir;

    private LocalFilesystemStorageNode node;

    @BeforeEach
    void setUp() {
        node = new LocalFilesystemStorageNode(7, tempDir);
    }

    @Test
    void writeThenReadRoundtripsBytes() throws IOException {
        byte[] data = "shard payload".getBytes(StandardCharsets.UTF_8);
        node.writeShard("obj-shard-0", data);
        assertThat(node.readShard("obj-shard-0")).isEqualTo(data);
    }

    @Test
    void writeMakesShardExist() throws IOException {
        node.writeShard("obj-shard-1", new byte[]{1, 2, 3});
        assertThat(node.shardExists("obj-shard-1")).isTrue();
    }

    @Test
    void missingShardReportsAsNotExisting() {
        assertThat(node.shardExists("never-written")).isFalse();
    }

    @Test
    void deleteRemovesShard() throws IOException {
        node.writeShard("ephemeral", new byte[]{9, 9});
        node.deleteShard("ephemeral");
        assertThat(node.shardExists("ephemeral")).isFalse();
    }

    @Test
    void deleteOnMissingShardIsNoOp() {
        // Must not throw — repair, GC, and abort flows all expect idempotent delete.
        org.assertj.core.api.Assertions.assertThatCode(() -> node.deleteShard("nope"))
                .doesNotThrowAnyException();
    }

    @Test
    void overwriteReplacesExistingBytes() throws IOException {
        node.writeShard("overwritten", new byte[]{1});
        node.writeShard("overwritten", new byte[]{2, 3, 4});
        assertThat(node.readShard("overwritten")).containsExactly(2, 3, 4);
    }

    @Test
    void listShardsReturnsEveryWrittenShard() throws IOException {
        node.writeShard("a", new byte[]{1});
        node.writeShard("b", new byte[]{2});
        node.writeShard("c", new byte[]{3});
        assertThat(node.listShards()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void listShardsOnEmptyNodeReturnsEmpty() throws IOException {
        assertThat(node.listShards()).isEmpty();
    }

    @Test
    void shardChecksumIsMD5OfBytes() throws Exception {
        byte[] data = "checksum me".getBytes(StandardCharsets.UTF_8);
        node.writeShard("cksum", data);

        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] expected = md.digest(data);

        assertThat(node.shardChecksum("cksum")).isEqualTo(expected);
    }

    @Test
    void nodeIdAccessorReturnsConstructorValue() {
        assertThat(node.nodeId()).isEqualTo(7);
    }
}
