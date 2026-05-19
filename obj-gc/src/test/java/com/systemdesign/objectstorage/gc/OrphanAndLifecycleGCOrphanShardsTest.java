package com.systemdesign.objectstorage.gc;

import com.systemdesign.objectstorage.model.GCResult;
import com.systemdesign.objectstorage.model.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Orphan-shard cleanup: shards on storage nodes with no metadata reference.
 * Within grace period → quarantined; past grace period → purged.
 */
class OrphanAndLifecycleGCOrphanShardsTest {

    private GCTestSupport.InMemoryNode node;
    private GCTestSupport.FakeMetadataRepo metadataRepo;
    private GCTestSupport.FakeUploadRepo uploadRepo;
    private GCTestSupport.FakePartRepo partRepo;
    private OrphanAndLifecycleGC gc;

    @BeforeEach
    void setUp() {
        node = new GCTestSupport.InMemoryNode(1);
        metadataRepo = new GCTestSupport.FakeMetadataRepo();
        uploadRepo = new GCTestSupport.FakeUploadRepo();
        partRepo = new GCTestSupport.FakePartRepo();

        Map<Integer, com.systemdesign.objectstorage.storage.StorageNode> nodes = new LinkedHashMap<>();
        nodes.put(1, node);
        gc = new OrphanAndLifecycleGC(nodes, metadataRepo, uploadRepo, partRepo);
    }

    @Test
    void orphanShardWithinGracePeriodIsQuarantinedNotPurged() {
        node.put("orphan-shard-0", new byte[]{1, 2, 3});

        GCResult result = gc.collect(new GCPolicy(Duration.ofHours(1), Duration.ofDays(7)));

        assertThat(result.orphansQuarantined()).isEqualTo(1);
        assertThat(result.orphansPurged()).isZero();
        assertThat(node.shardExists("orphan-shard-0")).isTrue();
    }

    @Test
    void orphanShardPastGracePeriodIsPurged() {
        node.put("orphan-shard-1", new byte[]{1, 2, 3});

        // First pass with non-zero grace → quarantine
        gc.collect(new GCPolicy(Duration.ofMillis(1), Duration.ofDays(7)));
        // Sleep so the grace period elapses
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        GCResult result = gc.collect(new GCPolicy(Duration.ofMillis(1), Duration.ofDays(7)));

        assertThat(result.orphansPurged()).isEqualTo(1);
        assertThat(node.shardExists("orphan-shard-1")).isFalse();
    }

    @Test
    void referencedShardIsNotConsideredOrphan() {
        ObjectMetadata meta = ObjectMetadata.builder()
                .bucket("b").objectKey("k")
                .totalSizeBytes(1).checksum("c=")
                .dataShards(4).parityShards(2)
                .baseShardId("base-A")
                .shardLocations("{}")
                .build();
        metadataRepo.save(meta);

        // The shard name matches one that would be referenced by base-A.
        node.put("base-A-shard-0", new byte[]{9});

        GCResult result = gc.collect(new GCPolicy(Duration.ofHours(1), Duration.ofDays(7)));

        assertThat(result.orphansQuarantined()).isZero();
        assertThat(result.orphansPurged()).isZero();
        assertThat(node.shardExists("base-A-shard-0")).isTrue();
    }

    @Test
    void multipleOrphansArePickedUpInOnePass() {
        node.put("orph-0", new byte[]{1});
        node.put("orph-1", new byte[]{1});
        node.put("orph-2", new byte[]{1});

        GCResult result = gc.collect(new GCPolicy(Duration.ofHours(1), Duration.ofDays(7)));

        assertThat(result.orphansQuarantined()).isEqualTo(3);
    }
}
