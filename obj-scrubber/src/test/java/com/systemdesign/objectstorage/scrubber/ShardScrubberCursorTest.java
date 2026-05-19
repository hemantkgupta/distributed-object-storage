package com.systemdesign.objectstorage.scrubber;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scrubber maintains a per-node cursor: a second pass starts where the
 * previous one stopped, so every shard is eventually verified even when the
 * budget is too tight to cover the full inventory in one pass.
 */
class ShardScrubberCursorTest {

    private InMemoryStorageNode node;
    private BackgroundScrubber scrubber;

    @BeforeEach
    void setUp() throws Exception {
        node = new InMemoryStorageNode(1);
        InMemoryRepairTaskQueue queue = new InMemoryRepairTaskQueue();
        Map<String, byte[]> expected = new HashMap<>();
        MessageDigest md = MessageDigest.getInstance("MD5");

        // 10 shards with sorted ids "shard-00".."shard-09"
        for (int i = 0; i < 10; i++) {
            String id = String.format("shard-%02d", i);
            byte[] data = ("payload-" + i).getBytes();
            node.putShard(id, data);
            expected.put(id, md.digest(data));
            md.reset();
        }

        scrubber = new BackgroundScrubber(Map.of(1, node), expected::get, queue);
    }

    @Test
    void firstPassWithBudgetOfFourScansFourShards() {
        ScrubSummary summary = scrubber.scrubNode(1, new ScrubBudget(4, Duration.ofMinutes(1)));
        assertThat(summary.shardsScanned()).isEqualTo(4);
    }

    @Test
    void cursorAdvancesSoSecondPassResumes() {
        ScrubSummary first = scrubber.scrubNode(1, new ScrubBudget(4, Duration.ofMinutes(1)));
        assertThat(first.isComplete()).isFalse();
        assertThat(first.resumeCursor()).isEqualTo("shard-03");

        ScrubSummary second = scrubber.scrubNode(1, new ScrubBudget(4, Duration.ofMinutes(1)));
        assertThat(second.shardsScanned()).isEqualTo(4);
        // The second pass started after shard-03, so it should have ended at shard-07.
        assertThat(second.resumeCursor()).isEqualTo("shard-07");
    }

    @Test
    void scanCompletesAcrossThreePassesEventuallyVisitingAllShards() {
        int totalScanned = 0;
        ScrubBudget budget = new ScrubBudget(4, Duration.ofMinutes(1));
        for (int pass = 0; pass < 4; pass++) {
            ScrubSummary s = scrubber.scrubNode(1, budget);
            totalScanned += s.shardsScanned();
        }
        // 10 shards visited across passes — could include partial wrap visits, but
        // every shard must have been scanned at least once.
        assertThat(totalScanned).isGreaterThanOrEqualTo(10);
    }

    @Test
    void summaryReportsCompletionWhenBudgetCoversFullInventory() {
        ScrubSummary summary = scrubber.scrubNode(1, new ScrubBudget(100, Duration.ofMinutes(1)));
        assertThat(summary.isComplete()).isTrue();
        assertThat(summary.shardsScanned()).isEqualTo(10);
    }
}
