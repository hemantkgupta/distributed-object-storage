package com.systemdesign.objectstorage.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link NodeStorageService}. The service writes to
 * {@code $user.dir/data/storage-nodes/node-{N}/}; we scope each test to a
 * unique shard-id prefix and clean those files up afterwards rather than the
 * whole directory tree, so concurrent or surrounding tests are unaffected.
 */
class NodeStorageServiceTest {

    private NodeStorageService service;
    private String runId;
    private Path baseDir;

    @BeforeEach
    void setUp() {
        service = new NodeStorageService();
        runId = "nss-test-" + UUID.randomUUID();
        baseDir = Paths.get(System.getProperty("user.dir"), "data", "storage-nodes");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (!Files.exists(baseDir)) return;
        try (Stream<Path> children = Files.list(baseDir)) {
            for (Path nodeDir : children.toList()) {
                if (!nodeDir.getFileName().toString().startsWith("node-")) continue;
                try (Stream<Path> shards = Files.list(nodeDir)) {
                    for (Path shard : shards.toList()) {
                        if (shard.getFileName().toString().startsWith(runId)) {
                            Files.deleteIfExists(shard);
                        }
                    }
                }
            }
        }
    }

    @Test
    void writeAndReadRoundtripsBytes() throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        String shardId = runId + "-shard-0";

        service.writeShard(3, shardId, data);

        assertThat(service.readShard(3, shardId)).isEqualTo(data);
    }

    @Test
    void writeCreatesFileUnderCorrectNodeDirectory() throws IOException {
        String shardId = runId + "-routing-check";
        service.writeShard(5, shardId, new byte[]{1, 2, 3});

        Path expected = baseDir.resolve("node-5").resolve(shardId);
        assertThat(Files.exists(expected)).isTrue();
    }

    @Test
    void shardExistsReturnsTrueAfterWrite() throws IOException {
        String shardId = runId + "-exists";
        service.writeShard(2, shardId, new byte[]{9});

        assertThat(service.shardExists(2, shardId)).isTrue();
    }

    @Test
    void shardExistsReturnsFalseForUnwrittenShard() {
        assertThat(service.shardExists(2, runId + "-never-written")).isFalse();
    }

    @Test
    void deleteRemovesShardFromDisk() throws IOException {
        String shardId = runId + "-delete-me";
        service.writeShard(1, shardId, new byte[]{4});
        service.deleteShard(1, shardId);
        assertThat(service.shardExists(1, shardId)).isFalse();
    }

    @Test
    void deleteOnMissingShardIsNoOp() {
        org.assertj.core.api.Assertions.assertThatCode(
                () -> service.deleteShard(1, runId + "-no-such-shard"))
                .doesNotThrowAnyException();
    }

    @Test
    void shardsOnDifferentNodesDoNotCollide() throws IOException {
        String shardId = runId + "-same-name";
        service.writeShard(1, shardId, new byte[]{1});
        service.writeShard(2, shardId, new byte[]{2});

        assertThat(service.readShard(1, shardId)).containsExactly(1);
        assertThat(service.readShard(2, shardId)).containsExactly(2);
    }
}
