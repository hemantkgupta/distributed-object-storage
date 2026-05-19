package com.systemdesign.objectstorage.scrubber;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the three outcomes the scrubber must distinguish:
 *   CLEAN — checksum matches → no repair task.
 *   CHECKSUM_MISMATCH — checksum differs → repair task enqueued.
 *   MISSING — shard file gone → repair task enqueued.
 */
class BackgroundScrubberTest {

    private InMemoryStorageNode node;
    private InMemoryRepairTaskQueue queue;
    private Map<String, byte[]> expectedChecksums;
    private BackgroundScrubber scrubber;

    @BeforeEach
    void setUp() {
        node = new InMemoryStorageNode(1);
        queue = new InMemoryRepairTaskQueue();
        expectedChecksums = new HashMap<>();

        Function<String, byte[]> lookup = expectedChecksums::get;
        scrubber = new BackgroundScrubber(Map.of(1, node), lookup, queue);
    }

    @Test
    void cleanShardProducesCleanOutcomeAndNoRepairTask() throws Exception {
        byte[] data = "clean-bytes".getBytes();
        node.putShard("ok-shard", data);
        expectedChecksums.put("ok-shard", md5(data));

        ScrubSummary summary = scrubber.scrubNode(1, new ScrubBudget(10, Duration.ofMinutes(1)));

        assertThat(summary.clean()).isEqualTo(1);
        assertThat(summary.corrupt()).isZero();
        assertThat(summary.missing()).isZero();
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    void mismatchedChecksumEnqueuesRepairTask() throws Exception {
        byte[] data = "corrupted-bytes".getBytes();
        node.putShard("bad-shard", data);
        // Expected checksum is for a DIFFERENT payload — simulates bit rot.
        expectedChecksums.put("bad-shard", md5("not-the-same".getBytes()));

        ScrubSummary summary = scrubber.scrubNode(1, new ScrubBudget(10, Duration.ofMinutes(1)));

        assertThat(summary.corrupt()).isEqualTo(1);
        assertThat(queue.enqueued).hasSize(1);
    }

    @Test
    void missingShardEnqueuesRepairTask() throws Exception {
        byte[] data = "gone-bytes".getBytes();
        node.putShard("missing-shard", data);
        expectedChecksums.put("missing-shard", md5(data));
        node.simulateMissing("missing-shard");

        ScrubSummary summary = scrubber.scrubNode(1, new ScrubBudget(10, Duration.ofMinutes(1)));

        assertThat(summary.missing()).isEqualTo(1);
        assertThat(queue.enqueued).hasSize(1);
    }

    @Test
    void shardWithoutMetadataReferenceIsTreatedAsCleanByScrubber() throws Exception {
        // Scrubber does not own orphan detection — that is GC's job. A shard with no
        // expected-checksum entry is reported as CLEAN (skipped), not corrupt.
        node.putShard("orphan-shard", "anything".getBytes());

        ScrubSummary summary = scrubber.scrubNode(1, new ScrubBudget(10, Duration.ofMinutes(1)));

        assertThat(summary.clean()).isEqualTo(1);
        assertThat(queue.enqueued).isEmpty();
    }

    @Test
    void unknownNodeIdProducesEmptySummary() {
        ScrubSummary summary = scrubber.scrubNode(999, new ScrubBudget(10, Duration.ofMinutes(1)));
        assertThat(summary.shardsScanned()).isZero();
    }

    private byte[] md5(byte[] data) throws Exception {
        return MessageDigest.getInstance("MD5").digest(data);
    }
}
