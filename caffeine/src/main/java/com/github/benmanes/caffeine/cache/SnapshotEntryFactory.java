package com.github.benmanes.caffeine.cache;

public class SnapshotEntryFactory {
    /** Returns a cache entry containing the given key, value, and snapshot. */
    public static <K, V> SnapshotEntry<K, V> forEntry(K key, V value) {
        return new SnapshotEntry<>(key, value, /* snapshot */ 0);
    }

    /** Returns a cache entry with the specified metadata. */
    public static <K, V> Policy.CacheEntry<K, V> forEntry(K key, V value,
                                                          long snapshot, int weight, long expiresAt, long refreshableAt) {
        long unsetTicks = snapshot + Long.MAX_VALUE;
        boolean refresh = (refreshableAt != unsetTicks);
        boolean expires = (expiresAt != unsetTicks);
        boolean weights = (weight != 1);
        int features = // truth table
                (weights ? 0b001 : 0b000)
                        | (expires ? 0b010 : 0b000)
                        | (refresh ? 0b100 : 0b000);
        switch (features) { // optimized for common cases
            case 0b000: return new SnapshotEntry<>(key, value, snapshot);
            case 0b001: return new SnapshotEntry.WeightedEntry<>(key, value, snapshot, weight);
            case 0b010: return new SnapshotEntry.ExpirableEntry<>(key, value, snapshot, expiresAt);
            case 0b011: return new SnapshotEntry.ExpirableWeightedEntry<>(key, value, snapshot, weight, expiresAt);
            case 0b110: return new SnapshotEntry.RefreshableExpirableEntry<>(
                    key, value, snapshot, expiresAt, refreshableAt);
            default: return new SnapshotEntry.CompleteEntry<>(key, value, snapshot, weight, expiresAt, refreshableAt, refreshableAt);
        }
    }
}
