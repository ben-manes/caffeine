package com.github.benmanes.caffeine.cache.simulator.policy.dash;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

import static java.lang.System.exit;

@PolicySpec(name = "dash.DashRust")
public class DashRustPolicy implements Policy.KeyOnlyPolicy {
    private final PolicyStats policyStats;

    static {
        System.loadLibrary("dash");
    }

    public DashRustPolicy(Config config) {
        this.policyStats = new PolicyStats(name());
        initCache();
    }

    @Override
    public PolicyStats stats() {
        return this.policyStats;
    }

    @Override
    public void record(long key) {
        long value = getFromCacheIfPresent(key);
        if (value == -1) {
            putToCache(key, key);
            policyStats.recordMiss();
        } else {
            policyStats.recordHit();
            if (key != value) {
                System.out.println("key != value in Dash cache");
                exit(1);
            }
        }
    }

    /* ---------------------------------------------------------------------------
     * Native (Rust) functions to create and drive Dash cache.
     * --------------------------------------------------------------------------- */

    /**
     * Creates the shared singleton instance of the Dash cache with given
     * parameters.
     */
    private static native void initCache();

    /**
     * TODO: the value type
     * Returns the value of the given key if exists. Otherwise returns -1.
     *
     * @return The weight of the key if exists. Otherwise -1.
     */
    private static native long getFromCacheIfPresent(long key);

    /**
     * TODO: the value type
     * Stores the value for the given key.
     */
    private static native void putToCache(long key, long value);

}
