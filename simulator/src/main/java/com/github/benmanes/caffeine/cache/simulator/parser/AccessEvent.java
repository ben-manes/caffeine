package com.github.benmanes.caffeine.cache.simulator.parser;

/**
 * An Immutable class to describe an Access event in trace.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public class AccessEvent {
    //required fields
    private long key;

    //optional fields - size is in bytes, and read rates are in bytes per ms
    private long missPenalty;
    private long hitPenalty;
    private long size;
    private long cacheReadRate;
    private long missReadRate;

    public long getKey(){
        return key;
    }

    public long getMissPenalty() {
        return  missPenalty;
    }

    public long getHitPenalty() {
        return hitPenalty;
    }

    public long getSize() {
        return size;
    }

    public long getCacheReadRate() {
        return cacheReadRate;
    }

    public long getMissReadRate() {
        return missReadRate;
    }

    public double calcMissPenalty() {
        return (double) size/missReadRate;
    }

    public double calcHitPenalty() {
        return (double) size/cacheReadRate;
    }

    private AccessEvent(AccessEventBuilder builder) {
        this.key = builder.key;
        this.size = builder.size;
        this.hitPenalty = builder.hitPenalty;
        this.missPenalty = builder.missPenalty;
        this.cacheReadRate = builder.cacheReadRate;
        this.missReadRate = builder.missReadRate;
    }

    //Builder class
    public static class AccessEventBuilder{
        //required fields
        private long key;

        //optional fields
        private long missPenalty;
        private long hitPenalty;
        private long size;
        private long cacheReadRate;
        private long missReadRate;

        public AccessEventBuilder(long key){
            this.key = key;
        }

        public AccessEventBuilder setMissPenalty(long missPenalty) {
            this.missPenalty = missPenalty;
            return this;
        }

        public AccessEventBuilder setHitPenalty(long hitPenalty) {
            this.hitPenalty = hitPenalty;
            return this;
        }

        public AccessEventBuilder setSize(long size) {
            this.size = size;
            return this;
        }

        public AccessEventBuilder setCacheReadRate(long cacheReadRate) {
            this.cacheReadRate = cacheReadRate;
            return this;
        }

        public AccessEventBuilder setMissReadRate(long missReadRate) {
            this.missReadRate = missReadRate;
            return this;
        }

        public AccessEvent build(){
            return new AccessEvent(this);
        }
    }
}
