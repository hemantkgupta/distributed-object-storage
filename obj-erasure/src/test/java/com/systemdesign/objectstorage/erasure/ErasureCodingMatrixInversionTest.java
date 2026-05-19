package com.systemdesign.objectstorage.erasure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that exercise the GF(2^8) Gaussian-elimination submatrix inversion that
 * powers degraded decode. For each loss pattern we drop exactly the shards
 * corresponding to a particular k-of-n selection from the generator matrix and
 * confirm the decoder reconstructs the original payload byte-for-byte.
 *
 * <p>The generator matrix is the identity rows (0..k-1) followed by Vandermonde
 * parity rows (k..k+m-1). Different shard-loss patterns force the decoder to
 * invert different k×k submatrices, so this test set covers a representative
 * subset of inversion paths.
 */
class ErasureCodingMatrixInversionTest {

    private ErasureCodingService service;
    private byte[] payload;
    private byte[][] shards;
    private int shardSize;

    @BeforeEach
    void setUp() {
        service = new ErasureCodingService();
        payload = new byte[4096];
        new Random(0xDEADBEEFL).nextBytes(payload);
        shards = service.encode(payload);
        shardSize = shards[0].length;
    }

    @Test
    void dataShardZeroLossUsesParityRowsInInverse() {
        // Drop data shard 0; surviving rows 1,2,3 (identity) + 4 (parity row [1,1,1,1])
        // force the inverse to extract a non-trivial column 0 from the parity row.
        byte[] recovered = decodeMissing(0);
        assertThat(recovered).isEqualTo(payload);
    }

    @Test
    void dataShardOneAndParityShardZeroLoss() {
        // Two losses force a k×k submatrix that mixes identity rows 0,2,3 with parity row 5.
        byte[] recovered = decodeMissing(1, 4);
        assertThat(recovered).isEqualTo(payload);
    }

    @Test
    void twoDataShardLossForcesBothParityRowsIntoInverse() {
        // Drop data shards 0 and 1; surviving = identity rows 2,3 + parity rows 4,5.
        // Both Vandermonde rows must contribute to the inverse — the heaviest path.
        byte[] recovered = decodeMissing(0, 1);
        assertThat(recovered).isEqualTo(payload);
    }

    @Test
    void dataShardTwoAndThreeLossUsesBothParityRows() {
        byte[] recovered = decodeMissing(2, 3);
        assertThat(recovered).isEqualTo(payload);
    }

    @Test
    void parityShardOnlyLossDoesNotRequireInversion() {
        // Surviving = identity rows 0..3 → submatrix is identity → inverse is identity.
        byte[] recovered = decodeMissing(4, 5);
        assertThat(recovered).isEqualTo(payload);
    }

    @Test
    void interiorDataLossMatchesEdgeDataLoss() {
        // Two distinct loss patterns must both round-trip — confirms inversion is correct
        // across different shard selections, not just one lucky alignment.
        byte[] recoveredA = decodeMissing(0, 2);
        byte[] recoveredB = decodeMissing(1, 3);
        assertThat(recoveredA).isEqualTo(payload);
        assertThat(recoveredB).isEqualTo(payload);
    }

    private byte[] decodeMissing(int... missing) {
        byte[][] mutable = new byte[shards.length][];
        for (int i = 0; i < shards.length; i++) {
            mutable[i] = shards[i].clone();
        }
        boolean[] present = new boolean[shards.length];
        Arrays.fill(present, true);
        for (int idx : missing) {
            present[idx] = false;
            mutable[idx] = null;
        }
        return service.decode(mutable, present, shardSize, payload.length);
    }
}
