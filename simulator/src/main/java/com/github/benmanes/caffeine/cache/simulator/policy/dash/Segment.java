package com.github.benmanes.caffeine.cache.simulator.policy.dash;

public class Segment {
    public Bucket[] buckets;
    public int numOfBuckets;

    public Segment(int size, int bucketsSize) {
        this.numOfBuckets = size;
        this.buckets = new Bucket[size];
        for (int i = 0; i < this.buckets.length; i++) {
            this.buckets[i] = new Bucket(bucketsSize);
        }
    }

    public int getBucketIndex(int hash) {
//        return (hash & 0x7FFFFFFF) % this.numOfBuckets;
        return hash % this.numOfBuckets;
    }
}
