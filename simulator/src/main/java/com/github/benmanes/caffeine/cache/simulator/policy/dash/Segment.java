package com.github.benmanes.caffeine.cache.simulator.policy.dash;

public class Segment {
    public Bucket[] buckets;
    public int size;

    public Segment(int size, int bucketsSize) {
        this.size = size;
        this.buckets = new Bucket[size];
        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i] = new Bucket(bucketsSize);
        }
    }

    public int getBucketIndex(int hash) {
        return (hash & 0x7FFFFFFF) % this.size;
    }
}
