package com.github.benmanes.caffeine.cache.simulator.policy.dash;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.CaseFormat;
import com.google.common.base.Enums;
import com.typesafe.config.Config;

import java.util.*;

import static java.util.Locale.US;

@PolicySpec(name = "dash.Dash")
public class DashPolicy implements Policy.KeyOnlyPolicy {
    // Caffeine stuff
    private PolicyStats policyStats;

    // Class fields
    private Segment[] segments;
    private int numOfSegments;
    private int segmentSize;
    private EvictionPolicy policy;
    private int debugMode;
    // TODO: erase
    private Set<Long> uniqueItems;

    public DashPolicy(Config config) {
        // Caffeine stuff
        this.policyStats = new PolicyStats(name());
        DashSettings settings = new DashSettings(config);

        // Class fields
        this.debugMode = settings.debugMode();
        this.numOfSegments = settings.numOfSegments();
        this.segmentSize = settings.segmentSize();

        this.policy = EvictionPolicy.LRU;
        this.segments = new Segment[this.numOfSegments];
        for (int i = 0; i < this.segments.length; i++) {
            this.segments[i] = new Segment(this.segmentSize, settings.bucketSize());
        }
    }

    public DashPolicy(DashSettings settings, EvictionPolicy policy) {
        this(
            settings.numOfSegments(),
            settings.segmentSize(),
            settings.bucketSize(),
            settings.debugMode(),
            policy
        );
    }

    public DashPolicy(int numOfSegments, int segmentSize, int bucketSize, int debugMode, EvictionPolicy policy) {
        // Caffeine stuff
        this.policyStats = new PolicyStats(name() + " #S = %d, #B = %d, #E = %d (%s)",
                numOfSegments,
                segmentSize,
                bucketSize,
                CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, policy.name()));

        // Class fields
        this.debugMode = debugMode;
        if (this.debugMode >= 1) {
            System.out.println("^^^^^^^^^^^^^^^^^^^^^^ Constructor ^^^^^^^^^^^^^^^^^^^^^^^^^^");
        }
        this.uniqueItems = new HashSet<>();
        this.numOfSegments = numOfSegments;
        this.segmentSize = segmentSize;

        this.policy = policy;
        this.segments = new Segment[this.numOfSegments];
        for (int i = 0; i < this.segments.length; i++) {
            this.segments[i] = new Segment(this.segmentSize, bucketSize);
        }
    }

    // TODO: implement
//    public static List<List<Integer>> getDifferentSizes

    public static Set<Policy> policies(Config config) {
        DashSettings settings = new DashSettings(config);
        Set<Policy> set = new HashSet<>();
        settings.policy()
            .forEach(policy -> {
                set.add(new DashPolicy(1, 4, 256, settings.debugMode(), policy));
                set.add(new DashPolicy(1, 8, 128, settings.debugMode(), policy));
                set.add(new DashPolicy(1, 16, 64, settings.debugMode(), policy));
                set.add(new DashPolicy(1, 32, 32, settings.debugMode(), policy));
                set.add(new DashPolicy(1, 64, 16, settings.debugMode(), policy));
                set.add(new DashPolicy(1, 128, 8, settings.debugMode(), policy));
                set.add(new DashPolicy(1, 256, 4, settings.debugMode(), policy));
            });
        return set;
    }

    @Override
    public PolicyStats stats() {
        return this.policyStats;
    }

    private int getNextBucketIndex(int index) {
        return (index != this.segmentSize - 1)
                ? index + 1
                : 0;
    }

    private int getPreviousBucketIndex(int index) {
        return (index != 0)
                ? index - 1
                : this.segmentSize - 1;
    }

    private int hash(Data data) {
        return Math.abs(data.hashCode());
    }

    private Segment getSegment(int hash) {
//        int segmentIndex = (hash & 0x7FFFFFFF) % this.numOfSegments;
        int segmentIndex = hash % this.numOfSegments;
        return this.segments[segmentIndex];
    }

    private void printDebug(long key) {
        System.out.println(String.format("===================== record (key = %d) ================================", key));
        for (int i = 0; i < this.numOfSegments; i++) {
            System.out.println(String.format("Segment = %d", i));
            for (int j = 0; j < this.segmentSize; j++) {
                System.out.println(String.format("  Bucket = %d", j));
                for (int h = 0; h < this.segments[i].buckets[j].size(); h++) {
                    System.out.println(String.format("    Entry %d = %d", h, this.segments[i].buckets[j].data_list.get(h).key));
                }
            }
        }
    }

    @Override
    public void record(long key) {
        if (this.debugMode == 3) {
            this.printDebug(key);
        }

        this.policyStats.recordOperation();
        if (this.debugMode == 3) {
            this.uniqueItems.add(key);
            if (this.uniqueItems.size() % 10000 == 0) {
                System.out.println(String.format("Unique = %d", this.uniqueItems.size()));
            }
        }
        Data data = new Data(key);

        int hash = this.hash(data);
        Segment targetSegment = getSegment(hash);
        int targetBucketIndex = targetSegment.getBucketIndex(hash);
        Bucket targetBucket = targetSegment.buckets[targetBucketIndex];

        Data dataFromBucket = targetBucket.get(data);
        if (dataFromBucket != null && dataFromBucket.key == key) {
            this.onHit(dataFromBucket, targetSegment, targetBucketIndex, true);
            if (this.debugMode >= 2) {
                System.out.println("############# Hit on target #############");
            }
        } else {
            int probingBucketIndex = getNextBucketIndex(targetBucketIndex);
            Bucket probingBucket = targetSegment.buckets[probingBucketIndex];
            dataFromBucket = probingBucket.get(data);

            if (dataFromBucket != null && dataFromBucket.key == key) {
                this.onHit(dataFromBucket, targetSegment, probingBucketIndex, false);
                if (this.debugMode >= 2) {
                    System.out.println("############# Hit on probing #############");
                }
            } else {
                this.onMiss(data, targetBucket, targetBucketIndex, probingBucket, targetSegment);
            }
        }
    }

    private void onHit(Data data, Segment segment, int bucketIndex, boolean isOnTarget) {
        this.policyStats.recordHit();

        switch (policy) {
            case MOVE_AHEAD:
                if (isOnTarget) {
                    Bucket nextBucket = segment.buckets[getNextBucketIndex(bucketIndex)];
                    // TODO: think of something better
                    if (!nextBucket.isFull()) {
                        segment.buckets[bucketIndex].remove(data);
                        segment.buckets[getNextBucketIndex(bucketIndex)].insert(data);
                    }
                }
                break;

            case LRU:
                segment.buckets[bucketIndex].remove(data);
                segment.buckets[bucketIndex].insert(data);
                break;

            case LFU:
            case LFU_DISP:
                data.LFUCounter++;
                break;

            case LIFO:
            case FIFO:
                break;
        }
    }

    private void onMiss(Data data,
                        Bucket targetBucket,
                        int targetBucketIndex,
                        Bucket probingBucket,
                        Segment targetSegment) {

        policyStats.recordMiss();
        if (this.debugMode >= 2) {
            System.out.println("############# onMiss #############");
        }

        if (targetBucket.isFull() && probingBucket.isFull()) {
            // Displacement
            Bucket bucket = this.displace(targetBucketIndex, targetSegment);
            // TODO: change to boolean
            if (bucket != null) {
                bucket.insert(data);
            } else {
                // TODO: think if there is a difference between evicting an item from
                // the target bucket or the probing bucket
                this.evictAndInsert(targetBucket, probingBucket, data);
            }
        } else {
            if (this.policy == EvictionPolicy.MOVE_AHEAD) {
                if (!targetBucket.isFull()) {
                    targetBucket.insert(data);
                } else {
                    // TODO: maybe put in probing bucket anyway
                    this.evictAndInsert(targetBucket, probingBucket, data);
                }
            } else {
                // Probing
                if (targetBucket.size() <= probingBucket.size()) {
                    targetBucket.insert(data);
                } else {
                    probingBucket.insert(data);
                }
            }
        }
    }

    private void evictAndInsert(Bucket targetBucket, Bucket probingBucket, Data data) {
        if (policy == EvictionPolicy.LFU_DISP) {
            Data targetLFU = targetBucket.getLFU();
            Data probingLFU = probingBucket.getLFU();
            if (targetLFU.LFUCounter <= probingLFU.LFUCounter) {
                targetBucket.evictItem(this.policy, this.policyStats, targetLFU);
                targetBucket.insert(data);
            } else {
                probingBucket.evictItem(this.policy, this.policyStats, probingLFU);
                probingBucket.insert(data);
            }
        } else {
            targetBucket.evictItem(this.policy, this.policyStats, null);
            targetBucket.insert(data);
        }
    }

    private interface isDisplacementPossible {
        boolean execute(Data data);
    }

    private Bucket displaceFromTo(isDisplacementPossible f, Bucket fromBucket, Bucket toBucket) {
        if (!toBucket.isFull()) {
            for (Data data : fromBucket.data_list) {
                if (f.execute(data)) {
                    if (this.debugMode >= 1) {
                        System.out.println("############# Displacement #############");
                    }
                    fromBucket.remove(data);
                    toBucket.insert(data);
                    return fromBucket;
                }
            }
        }
        return null;
    }

    private Bucket displace(int targetBucketIndex, Segment targetSegment) {
        Bucket res = null;

        final int targetToBucketIndex = this.getPreviousBucketIndex(targetBucketIndex);
        if (!targetSegment.buckets[targetToBucketIndex].isFull()) {
            res = this.displaceFromTo(
                    (Data data) -> targetSegment.getBucketIndex(this.hash(data)) == targetToBucketIndex,
                    targetSegment.buckets[targetBucketIndex],
                    targetSegment.buckets[targetToBucketIndex]
            );
        }

        if (res == null) {
            int probingBucketIndex = this.getNextBucketIndex(targetBucketIndex);
            final int probingToBucketIndex = this.getNextBucketIndex(probingBucketIndex);
            if (!targetSegment.buckets[probingToBucketIndex].isFull()) {
                res = this.displaceFromTo(
                        (Data data) -> targetSegment.getBucketIndex(this.hash(data)) == probingToBucketIndex,
                        targetSegment.buckets[probingBucketIndex],
                        targetSegment.buckets[probingToBucketIndex]
                );
            }
        }
        return res;
    }

    public enum EvictionPolicy {
        MOVE_AHEAD,
        LRU,
        LIFO,
        LFU,
        LFU_DISP,
        FIFO;
    }

    public static final class DashSettings extends BasicSettings {

        public DashSettings(Config config) {
            super(config);
        }

        public int numOfSegments() {
            return this.config().getInt("dash.numOfSegments");
        }

        public int segmentSize() {
            return this.config().getInt("dash.segmentSize");
        }

        public int bucketSize() {
            return this.config().getInt("dash.bucketSize");
        }

        public int debugMode() {
            return this.config().getInt("dash.debugMode");
        }

        public Set<EvictionPolicy> policy() {
            var policies = EnumSet.noneOf(EvictionPolicy.class);
            for (var policy : config().getStringList("dash.policy")) {
                var option = Enums.getIfPresent(EvictionPolicy.class, policy.toUpperCase(US)).toJavaUtil();
                option.ifPresentOrElse(policies::add, () -> {
                    throw new IllegalArgumentException("Unknown policy: " + policy);
                });
            }
            return policies;
        }
    }
}
