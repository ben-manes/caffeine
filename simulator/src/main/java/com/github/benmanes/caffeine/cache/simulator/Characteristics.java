package com.github.benmanes.caffeine.cache.simulator;
/**
 * Enumeration to describe an Access event supported characteristics by policy or trace reader.
 *
 * @author himelbrand@gmail.com (Omri Himelbrand)
 */
public enum Characteristics {
    KEY,
    MISS_PENALTY,
    HIT_PENALTY,
    SIZE,
    CACHE_READ_RATE,
    MISS_READ_RATE
}
