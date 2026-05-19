package com.systemdesign.objectstorage.erasure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for degraded-read behaviour: dropping 1, 2, and 3 shards from a
 * fully-encoded payload and verifying the {@code (k=4, m=2)} tolerance boundary.
 */
class ErasureCodingDegradedReadTest {

    private static final byte[] PAYLOAD =
            "degraded read fixture payload".getBytes(StandardCharsets.UTF_8);

    private ErasureCodingService service;
    private byte[][] shards;
    private int shardSize;

    @BeforeEach
    void setUp() {
        service = new ErasureCodingService();
        shards = service.encode(PAYLOAD);
        shardSize = shards[0].length;
    }

    @Test
    void dropOneShardStillDecodes() {
        boolean[] present = allPresent();
        present[2] = false;
        shards[2] = null;

        byte[] recovered = service.decode(shards, present, shardSize, PAYLOAD.length);
        assertThat(recovered).isEqualTo(PAYLOAD);
    }

    @Test
    void dropTwoShardsStillDecodes() {
        boolean[] present = allPresent();
        present[1] = false;
        present[5] = false;
        shards[1] = null;
        shards[5] = null;

        byte[] recovered = service.decode(shards, present, shardSize, PAYLOAD.length);
        assertThat(recovered).isEqualTo(PAYLOAD);
    }

    @Test
    void dropThreeShardsFailsToDecode() {
        boolean[] present = allPresent();
        present[0] = false;
        present[1] = false;
        present[4] = false;

        assertThatThrownBy(() -> service.decode(shards, present, shardSize, PAYLOAD.length))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Need at least");
    }

    @Test
    void dropAllParityStillDecodes() {
        boolean[] present = allPresent();
        present[4] = false;
        present[5] = false;
        shards[4] = null;
        shards[5] = null;

        byte[] recovered = service.decode(shards, present, shardSize, PAYLOAD.length);
        assertThat(recovered).isEqualTo(PAYLOAD);
    }

    private boolean[] allPresent() {
        boolean[] present = new boolean[shards.length];
        Arrays.fill(present, true);
        return present;
    }
}
