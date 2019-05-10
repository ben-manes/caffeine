package com.github.benmanes.caffeine.cache.simulator.membership.bloom;

import java.util.Arrays;

import com.github.benmanes.caffeine.cache.simulator.membership.Membership;

public class BlockedBloom implements Membership {

    private final int buckets;
    private final long seed;
    private final long[] data;

    public BlockedBloom(long expectedInsertions, double fpp) {
        // the fpp parameter is ignored; fpp is about 1%
        int bitsPerKey = 11;
        long entryCount = (int) Math.max(1, expectedInsertions);
        this.seed = Hash.randomSeed();
        long bits = entryCount * bitsPerKey;
        if ((bits / 64) + 16 >= Integer.MAX_VALUE) {
            // an alternative is: use a smaller array, and let the fpp increase
            // (use an overfull filter)
            throw new IllegalArgumentException("Max capacity exceeded: " + expectedInsertions);
        }
        this.buckets = (int) (bits / 64);
        data = new long[(int) (buckets + 16)];
    }

    @Override
    public boolean mightContain(long key) {
        long hash = Hash.hash64(key, seed);
        int start = Hash.reduce((int) hash, buckets);
        hash = hash ^ Long.rotateLeft(hash, 32);
        long a = data[start];
        long b = data[start + 1 + (int) (hash >>> 60)];
        long m1 = (1L << hash) | (1L << (hash >> 6));
        long m2 = (1L << (hash >> 12)) | (1L << (hash >> 18));
        return ((m1 & a) == m1) && ((m2 & b) == m2);
    }

    @Override
    public boolean put(long key) {
        long hash = Hash.hash64(key, seed);
        int start = Hash.reduce((int) hash, buckets);
        hash = hash ^ Long.rotateLeft(hash, 32);
        long m1 = (1L << hash) | (1L << (hash >> 6));
        long m2 = (1L << (hash >> 12)) | (1L << (hash >> 18));
        data[start] |= m1;
        data[start + 1] |= m2;
        return true;
    }

    @Override
    public void clear() {
        Arrays.fill(data, 0);
    }

}
