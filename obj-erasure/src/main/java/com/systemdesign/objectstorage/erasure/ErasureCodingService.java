package com.systemdesign.objectstorage.erasure;

import org.springframework.stereotype.Service;

/**
 * Reed-Solomon erasure coding over GF(2^8).
 *
 * Configuration: k=4 data shards + m=2 parity shards.
 * Any 4 of the 6 shards are sufficient to reconstruct the original data.
 *
 * Implementation notes:
 *   - Self-contained — no external library. GF(2^8) arithmetic uses exp/log
 *     tables with primitive polynomial 0x11D (x^8 + x^4 + x^3 + x^2 + 1).
 *   - Encoding matrix: identity rows (k) + Vandermonde rows (m).
 *     Row k+j = [α^(j*0), α^(j*1), ..., α^(j*(k-1))] where α=2 in GF(2^8).
 *   - Decode: select k available shard rows from the generator matrix, invert
 *     that k×k submatrix in GF(2^8), multiply by available shard bytes.
 */
@Service
public class ErasureCodingService {

    public static final int DATA_SHARDS   = 4;
    public static final int PARITY_SHARDS = 2;
    public static final int TOTAL_SHARDS  = DATA_SHARDS + PARITY_SHARDS;

    // ── GF(2^8) tables ────────────────────────────────────────────────────
    // Primitive polynomial: x^8 + x^4 + x^3 + x^2 + 1 = 0x11D
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        // Duplicate exp table so we can index without modular arithmetic
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
        LOG[0] = 0; // undefined, but avoids NPE if ever reached
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    private static int gfDiv(int a, int b) {
        if (b == 0) throw new ArithmeticException("GF div by zero");
        if (a == 0) return 0;
        return EXP[LOG[a] - LOG[b] + 255];
    }

    private static int gfPow(int base, int exp) {
        return EXP[(LOG[base] * exp) % 255];
    }

    // ── Generator matrix (TOTAL_SHARDS × DATA_SHARDS) ────────────────────
    // Rows 0..k-1: identity matrix (data shard passthrough)
    // Rows k..k+m-1: Vandermonde rows  row k+j = [α^(0j), α^(1j), ..., α^((k-1)j)]

    private static final int[][] GENERATOR = buildGenerator();

    private static int[][] buildGenerator() {
        int[][] g = new int[TOTAL_SHARDS][DATA_SHARDS];
        // Identity rows
        for (int i = 0; i < DATA_SHARDS; i++) g[i][i] = 1;
        // Vandermonde parity rows
        for (int j = 0; j < PARITY_SHARDS; j++) {
            for (int c = 0; c < DATA_SHARDS; c++) {
                // α^(j * c) where α = 2
                g[DATA_SHARDS + j][c] = (j == 0) ? 1 : gfPow(gfPow(2, c), j);
            }
        }
        // Simpler: row k+0 = [1,1,1,1], row k+1 = [1,2,4,8]
        for (int c = 0; c < DATA_SHARDS; c++) {
            g[DATA_SHARDS][c]     = 1;           // XOR of all data shards
            g[DATA_SHARDS + 1][c] = gfPow(2, c); // 1, 2, 4, 8
        }
        return g;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Encodes a payload into DATA_SHARDS data shards + PARITY_SHARDS parity shards.
     * The returned array has TOTAL_SHARDS rows, each of length ceil(payload/k).
     */
    public byte[][] encode(byte[] payload) {
        int shardSize  = (payload.length + DATA_SHARDS - 1) / DATA_SHARDS;
        int bufferSize = shardSize * DATA_SHARDS;

        byte[] padded = new byte[bufferSize];
        System.arraycopy(payload, 0, padded, 0, payload.length);

        byte[][] shards = new byte[TOTAL_SHARDS][shardSize];

        // Fill data shards
        for (int i = 0; i < DATA_SHARDS; i++) {
            System.arraycopy(padded, i * shardSize, shards[i], 0, shardSize);
        }

        // Compute parity shards via generator matrix rows [k..k+m-1]
        for (int p = DATA_SHARDS; p < TOTAL_SHARDS; p++) {
            for (int b = 0; b < shardSize; b++) {
                int val = 0;
                for (int d = 0; d < DATA_SHARDS; d++) {
                    val ^= gfMul(GENERATOR[p][d], shards[d][b] & 0xFF);
                }
                shards[p][b] = (byte) val;
            }
        }

        return shards;
    }

    /**
     * Reconstructs the original payload from surviving shards.
     * shards[i] may be null/garbage if shardPresent[i] is false.
     * At least DATA_SHARDS entries in shardPresent must be true.
     */
    public byte[] decode(byte[][] shards, boolean[] shardPresent, int shardSize, int originalLength) {
        // Identify which shards are available (take the first k)
        int[] availableIdx = new int[DATA_SHARDS];
        int count = 0;
        for (int i = 0; i < TOTAL_SHARDS && count < DATA_SHARDS; i++) {
            if (shardPresent[i]) availableIdx[count++] = i;
        }
        if (count < DATA_SHARDS) {
            throw new IllegalStateException("Need at least " + DATA_SHARDS + " shards, only " + count + " available");
        }

        // Extract the k×k submatrix of the generator corresponding to available shards
        int[][] subMatrix = new int[DATA_SHARDS][DATA_SHARDS];
        byte[][] subShards = new byte[DATA_SHARDS][shardSize];
        for (int r = 0; r < DATA_SHARDS; r++) {
            subMatrix[r] = GENERATOR[availableIdx[r]].clone();
            subShards[r] = shards[availableIdx[r]];
        }

        // Invert the submatrix in GF(2^8)
        int[][] inv = invertMatrix(subMatrix);

        // Multiply inverse by available shard bytes to recover original data shards
        byte[][] dataShards = new byte[DATA_SHARDS][shardSize];
        for (int d = 0; d < DATA_SHARDS; d++) {
            for (int b = 0; b < shardSize; b++) {
                int val = 0;
                for (int r = 0; r < DATA_SHARDS; r++) {
                    val ^= gfMul(inv[d][r], subShards[r][b] & 0xFF);
                }
                dataShards[d][b] = (byte) val;
            }
        }

        // Concatenate data shards and trim to originalLength
        byte[] result = new byte[originalLength];
        int copied = 0;
        for (int d = 0; d < DATA_SHARDS && copied < originalLength; d++) {
            int toCopy = Math.min(shardSize, originalLength - copied);
            System.arraycopy(dataShards[d], 0, result, copied, toCopy);
            copied += toCopy;
        }
        return result;
    }

    // ── GF(2^8) matrix inversion via Gaussian elimination ────────────────

    private static int[][] invertMatrix(int[][] m) {
        int n = m.length;
        // Augment: [m | I]
        int[][] aug = new int[n][2 * n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(m[r], 0, aug[r], 0, n);
            aug[r][n + r] = 1;
        }

        // Forward elimination
        for (int col = 0; col < n; col++) {
            // Find pivot row
            int pivot = -1;
            for (int row = col; row < n; row++) {
                if (aug[row][col] != 0) { pivot = row; break; }
            }
            if (pivot < 0) throw new ArithmeticException("Matrix is singular");
            // Swap pivot row to position col
            int[] tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp;
            // Scale pivot row so diagonal becomes 1
            int scale = aug[col][col];
            for (int c = 0; c < 2 * n; c++) aug[col][c] = gfDiv(aug[col][c], scale);
            // Eliminate column in all other rows
            for (int row = 0; row < n; row++) {
                if (row == col || aug[row][col] == 0) continue;
                int factor = aug[row][col];
                for (int c = 0; c < 2 * n; c++) {
                    aug[row][c] ^= gfMul(factor, aug[col][c]);
                }
            }
        }

        // Extract right half (the inverse)
        int[][] inv = new int[n][n];
        for (int r = 0; r < n; r++) {
            System.arraycopy(aug[r], n, inv[r], 0, n);
        }
        return inv;
    }
}
