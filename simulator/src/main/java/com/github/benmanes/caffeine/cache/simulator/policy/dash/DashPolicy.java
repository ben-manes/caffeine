package com.github.benmanes.caffeine.cache.simulator.policy.dash;


import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

@PolicySpec(name = "dash.Dash")
public class DashPolicy implements Policy.KeyOnlyPolicy {
    // Caffeine stuff
    private final PolicyStats policyStats;

    // Class fields
    private final Segment[] segments;
    private final int size;
    private final int segmentSize;
    private final EvictionPolicy policy;

    public DashPolicy(Config config) {
        // Caffeine stuff
        this.policyStats = new PolicyStats(name());

        // Class fields
        this.size = config.getInt("dash.size");
        this.segmentSize = config.getInt("dash.segmentSize");;
        this.policy = EvictionPolicy.LRU;
        this.segments = new Segment[size];
        for (int i = 0; i < this.segments.length; i++) {
            this.segments[i] = new Segment(config.getInt("dash.bucketSize"), 5);
        }
    }

    @Override
    public PolicyStats stats() {
        return this.policyStats;
    }

    @Override
    public void record(long key) {
        this.policyStats.recordOperation();
//        this.admittor.record(key);
        Data data = new Data(key);

        int hash = hash(data);
        Segment targetSegment = getSegment(hash);
        int targetBucketIndex = targetSegment.getBucketIndex(hash);
        Bucket targetBucket = targetSegment.buckets[targetBucketIndex];

        Data dataFromBucket = targetBucket.get(data);
        if (dataFromBucket != null) {
            this.onHit(dataFromBucket, targetBucket);
        } else {
            int probingBucketIndex = targetBucketIndex + 1;
            Bucket probingBucket = null;
            if (probingBucketIndex < this.segmentSize) {
                probingBucket = targetSegment.buckets[probingBucketIndex];
                dataFromBucket = probingBucket.get(data);
            }

            if (dataFromBucket != null) {
                this.onHit(dataFromBucket, probingBucket);
            } else {
                this.onMiss(data, targetBucket, targetBucketIndex, probingBucket, targetSegment);
            }
        }
    }

    private void onHit(Data data, Bucket bucket) {
        if(this.policy == EvictionPolicy.LRU) {
            bucket.remove(data);
            bucket.insert(data);
        }
        this.policyStats.recordHit();
    }

    private void onMiss(Data data,
                        Bucket targetBucket,
                        int targetBucketIndex,
                        Bucket probingBucket,
                        Segment targetSegment) {
        policyStats.recordMiss();

        if (targetBucket.isFull() && (probingBucket == null || probingBucket.isFull())) {
            // Displacement
            Bucket bucket = this.displace(targetBucketIndex, targetSegment);
            if (bucket != null) {
                bucket.insert(data);
            } else {
                // TODO: think if there is a difference between evicting an item from
                // the target bucket or the probing bucket
                policyStats.recordEviction();
                targetBucket.evictItem(policy);
                targetBucket.insert(data);
            }
        } else {
            // Probing
            if (probingBucket == null || targetBucket.count <= probingBucket.count) {
                targetBucket.insert(data);
            } else {
                probingBucket.insert(data);
            }
        }
    }

    private int hash(Data data) {
        return data.hashCode();
    }

    private Segment getSegment(int hash) {
        int segmentIndex = (hash & 0x7FFFFFFF) % this.size;
        return this.segments[segmentIndex];
    }

    private Bucket displace(int targetBucketIndex, Segment targetSegment) {
        Bucket res = displaceTargetBucket(targetBucketIndex, targetSegment);
        if (res == null) {
            res = displaceProbingBucket(targetBucketIndex + 1, targetSegment);
        }
        return res;
    }

    private Bucket displaceTargetBucket(int targetBucketIndex, Segment targetSegment) {
        if (targetBucketIndex == 0) {
            return null;
        }

        // Try to move a record from TargetBucket to TargetBucket - 1
        Bucket displacementBucket = targetSegment.buckets[targetBucketIndex - 1];

        if (!displacementBucket.isFull()) {
            Bucket targetBucket = targetSegment.buckets[targetBucketIndex];
            for (Data data : targetBucket.data_list) {
                if (targetSegment.getBucketIndex(hash(data)) == targetBucketIndex - 1) {
                    targetBucket.remove(data);
                    displacementBucket.insert(data);
                    return targetBucket;
                }
            }
        }
        return null;
    }

    private Bucket displaceProbingBucket(int probingBucketIndex, Segment targetSegment) {
        if (probingBucketIndex + 1 >= targetSegment.buckets.length) {
            return null;
        }
        // Try to move a record from ProbingBucket to ProbingBucket + 1
        Bucket displacementBucket = targetSegment.buckets[probingBucketIndex + 1];

        if (!displacementBucket.isFull()) {
            Bucket probingBucket = targetSegment.buckets[probingBucketIndex];
            for (Data data : probingBucket.data_list) {
                if (targetSegment.getBucketIndex(hash(data)) == probingBucketIndex) {
                    probingBucket.remove(data);
                    displacementBucket.insert(data);
                    return probingBucket;
                }
            }
        }
        return null;
    }
}
