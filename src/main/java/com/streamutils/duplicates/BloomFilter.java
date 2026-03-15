package com.streamutils.duplicates;

import java.util.BitSet;

/**
 * A space-efficient probabilistic set membership filter.
 *
 * <p>
 * A Bloom filter can tell you with certainty that an element is <em>not</em>
 * in the set, but may produce false positives (reporting an element is present
 * when it is not). It never produces false negatives.
 *
 * <p>
 * This implementation uses double-hashing (two independent seeds combined
 * into {@code k} hash probes) to avoid requiring {@code k} separate hash
 * function implementations.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Bloom_filter">Bloom filter
 *      (Wikipedia)</a>
 */
final class BloomFilter {

    private final BitSet bits;
    private final int bitSize;
    private final int numHashFunctions;

    /**
     * Creates a Bloom filter tuned for the expected number of insertions and
     * the desired false-positive probability.
     *
     * @param expectedInsertions expected number of distinct elements
     * @param falsePositiveRate  desired false-positive probability (e.g. 0.01 for
     *                           1%)
     * @throws IllegalArgumentException if parameters are non-positive
     */
    BloomFilter(long expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be > 0");
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0, 1)");
        }

        // Optimal bit-array size: m = -n * ln(p) / (ln2)^2
        this.bitSize = optimalBitSize(expectedInsertions, falsePositiveRate);
        // Optimal number of hash functions: k = (m / n) * ln2
        this.numHashFunctions = optimalNumHashFunctions(expectedInsertions, bitSize);
        this.bits = new BitSet(bitSize);
    }

    /**
     * Records an element as present in the filter.
     *
     * @param item the item to add (uses its {@code hashCode()})
     */
    void put(Object item) {
        long hash64 = spread(item.hashCode());
        int h1 = (int) hash64;
        int h2 = (int) (hash64 >>> 32);

        for (int i = 0; i < numHashFunctions; i++) {
            int combinedHash = h1 + i * h2;
            bits.set(positiveIndex(combinedHash));
        }
    }

    /**
     * Tests whether an element <em>might</em> be in the set.
     *
     * @param item the item to test
     * @return {@code false} if the element is definitely not in the set;
     *         {@code true} if it might be (with probability ≤ the configured FPR)
     */
    boolean mightContain(Object item) {
        long hash64 = spread(item.hashCode());
        int h1 = (int) hash64;
        int h2 = (int) (hash64 >>> 32);

        for (int i = 0; i < numHashFunctions; i++) {
            int combinedHash = h1 + i * h2;
            if (!bits.get(positiveIndex(combinedHash))) {
                return false;
            }
        }
        return true;
    }

    // --- internals ---

    /**
     * Avalanche-mix a 32-bit hash into a 64-bit value to produce two
     * independent-ish hashes for double-hashing. Inspired by MurmurHash3
     * finalizer.
     */
    private static long spread(int h) {
        long x = h;
        x = ((x >> 16) ^ x) * 0x45d9f3bL;
        x = ((x >> 16) ^ x) * 0x45d9f3bL;
        x = (x >> 16) ^ x;
        // Mirror into upper 32 bits with a different constant
        long y = h;
        y = ((y >> 16) ^ y) * 0x119de1f3L;
        y = ((y >> 16) ^ y) * 0x119de1f3L;
        y = (y >> 16) ^ y;
        return (y << 32) | (x & 0xFFFFFFFFL);
    }

    private int positiveIndex(int hash) {
        return Math.floorMod(hash, bitSize);
    }

    private static int optimalBitSize(long n, double p) {
        long m = (long) Math.ceil(-n * Math.log(p) / (Math.log(2) * Math.log(2)));
        return (int) Math.min(m, Integer.MAX_VALUE);
    }

    private static int optimalNumHashFunctions(long n, int m) {
        int k = Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
        return k;
    }
}
