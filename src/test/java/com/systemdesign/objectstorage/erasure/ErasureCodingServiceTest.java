package com.systemdesign.objectstorage.erasure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Reed-Solomon erasure coding over GF(2^8).
 *
 * Covers: encode/decode roundtrip, degraded decode with 1 missing shard,
 * degraded decode with 2 missing shards (maximum tolerance), unrecoverable
 * with 3 missing shards, empty payload, large payload, single-byte payload,
 * and shard size consistency.
 */
class ErasureCodingServiceTest {

    private ErasureCodingService service;

    @BeforeEach
    void setUp() {
        service = new ErasureCodingService();
    }

    // ── Encode / Decode Roundtrip ──────────────────────────────────────

    @Test
    void encodeAndDecodeRoundtrip_allShardsPresent() {
        byte[] original = "Hello, erasure coding!".getBytes(StandardCharsets.UTF_8);

        byte[][] shards = service.encode(original);
        assertEquals(ErasureCodingService.TOTAL_SHARDS, shards.length);

        boolean[] present = allPresent(shards.length);
        byte[] recovered = service.decode(shards, present, shards[0].length, original.length);
        assertArrayEquals(original, recovered);
    }

    @Test
    void encodeAndDecodeRoundtrip_largePayload() {
        byte[] original = new byte[100_000];
        new Random(42).nextBytes(original);

        byte[][] shards = service.encode(original);
        boolean[] present = allPresent(shards.length);
        byte[] recovered = service.decode(shards, present, shards[0].length, original.length);
        assertArrayEquals(original, recovered);
    }

    @Test
    void encodeAndDecodeRoundtrip_singleByte() {
        byte[] original = new byte[]{0x42};

        byte[][] shards = service.encode(original);
        boolean[] present = allPresent(shards.length);
        byte[] recovered = service.decode(shards, present, shards[0].length, original.length);
        assertArrayEquals(original, recovered);
    }

    @Test
    void encodeAndDecodeRoundtrip_emptyPayload() {
        byte[] original = new byte[0];

        byte[][] shards = service.encode(original);
        // All shards should be zero-length or minimal
        boolean[] present = allPresent(shards.length);
        byte[] recovered = service.decode(shards, present, shards[0].length, original.length);
        assertArrayEquals(original, recovered);
    }

    // ── Degraded Decode (1 missing shard) ─────────────────────────────

    @Test
    void decode_oneMissingShard_dataShardMissing() {
        byte[] original = "Degraded read with one data shard missing".getBytes(StandardCharsets.UTF_8);
        byte[][] shards = service.encode(original);

        // Remove data shard 1
        boolean[] present = allPresent(shards.length);
        present[1] = false;
        shards[1] = null;

        byte[] recovered = service.decode(shards, present, shards[0].length, original.length);
        assertArrayEquals(original, recovered);
    }

    @Test
    void decode_oneMissingShard_parityShardMissing() {
        byte[] original = "Degraded read with one parity shard missing".getBytes(StandardCharsets.UTF_8);
        byte[][] shards = service.encode(original);

        // Remove parity shard 0 (index 4)
        boolean[] present = allPresent(shards.length);
        present[4] = false;
        shards[4] = null;

        byte[] recovered = service.decode(shards, present, shards[0].length, original.length);
        assertArrayEquals(original, recovered);
    }

    // ── Degraded Decode (2 missing shards — maximum tolerance) ────────

    @Test
    void decode_twoMissingShards_bothDataShards() {
        byte[] original = "Two data shards missing — maximum tolerance".getBytes(StandardCharsets.UTF_8);
        byte[][] shards = service.encode(original);

        boolean[] present = allPresent(shards.length);
        present[0] = false;
        shards[0] = null;
        present[2] = false;
        shards[2] = null;

        byte[] recovered = service.decode(shards, present, shards[1].length, original.length);
        assertArrayEquals(original, recovered);
    }

    @Test
    void decode_twoMissingShards_bothParityShards() {
        byte[] original = "Both parity shards missing — data shards alone suffice".getBytes(StandardCharsets.UTF_8);
        byte[][] shards = service.encode(original);

        boolean[] present = allPresent(shards.length);
        present[4] = false;
        shards[4] = null;
        present[5] = false;
        shards[5] = null;

        // With both parity gone, the 4 data shards are exactly k — identity decode
        byte[] recovered = service.decode(shards, present, shards[0].length, original.length);
        assertArrayEquals(original, recovered);
    }

    @Test
    void decode_twoMissingShards_oneDataOneParity() {
        byte[] original = "One data + one parity missing".getBytes(StandardCharsets.UTF_8);
        byte[][] shards = service.encode(original);

        boolean[] present = allPresent(shards.length);
        present[3] = false;
        shards[3] = null;
        present[5] = false;
        shards[5] = null;

        byte[] recovered = service.decode(shards, present, shards[0].length, original.length);
        assertArrayEquals(original, recovered);
    }

    // ── Unrecoverable (3+ missing shards) ─────────────────────────────

    @Test
    void decode_threeMissingShards_throwsException() {
        byte[] original = "Too many shards missing".getBytes(StandardCharsets.UTF_8);
        byte[][] shards = service.encode(original);

        boolean[] present = allPresent(shards.length);
        present[0] = false;
        present[2] = false;
        present[4] = false;

        assertThrows(IllegalStateException.class, () ->
                service.decode(shards, present, shards[1].length, original.length));
    }

    // ── Shard Properties ──────────────────────────────────────────────

    @Test
    void encode_producesCorrectNumberOfShards() {
        byte[] original = "shard count check".getBytes(StandardCharsets.UTF_8);
        byte[][] shards = service.encode(original);

        assertEquals(ErasureCodingService.DATA_SHARDS, 4);
        assertEquals(ErasureCodingService.PARITY_SHARDS, 2);
        assertEquals(ErasureCodingService.TOTAL_SHARDS, 6);
        assertEquals(shards.length, 6);
    }

    @Test
    void encode_allShardsHaveSameSize() {
        byte[] original = "all shards equal size".getBytes(StandardCharsets.UTF_8);
        byte[][] shards = service.encode(original);

        int expectedSize = shards[0].length;
        for (int i = 1; i < shards.length; i++) {
            assertEquals(expectedSize, shards[i].length,
                    "Shard " + i + " has different size than shard 0");
        }
    }

    @Test
    void encode_shardSizeIsCeilOfPayloadDividedByK() {
        byte[] original = new byte[10]; // 10 bytes / 4 shards = ceil(2.5) = 3
        byte[][] shards = service.encode(original);
        assertEquals(3, shards[0].length);
    }

    @Test
    void encode_dataShardsAreChunksOfOriginal() {
        // For a payload that divides evenly by k, data shards should be
        // exact chunks of the original (identity rows in generator matrix)
        byte[] original = new byte[]{1, 2, 3, 4, 5, 6, 7, 8}; // 8 bytes / 4 = 2 bytes per shard
        byte[][] shards = service.encode(original);

        assertArrayEquals(new byte[]{1, 2}, shards[0]); // data shard 0
        assertArrayEquals(new byte[]{3, 4}, shards[1]); // data shard 1
        assertArrayEquals(new byte[]{5, 6}, shards[2]); // data shard 2
        assertArrayEquals(new byte[]{7, 8}, shards[3]); // data shard 3
    }

    @Test
    void encode_parityShard0IsXorOfAllDataShards() {
        // Generator row 4 = [1,1,1,1] → parity 0 = XOR of all data shards
        byte[] original = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[][] shards = service.encode(original);

        for (int b = 0; b < shards[0].length; b++) {
            int expected = (shards[0][b] ^ shards[1][b] ^ shards[2][b] ^ shards[3][b]) & 0xFF;
            assertEquals(expected, shards[4][b] & 0xFF,
                    "Parity shard 0 byte " + b + " is not XOR of data shards");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private boolean[] allPresent(int length) {
        boolean[] present = new boolean[length];
        Arrays.fill(present, true);
        return present;
    }
}
