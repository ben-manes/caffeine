package com.github.benmanes.caffeine.cache;

import org.junit.Assert;
import org.junit.Test;

import java.io.Console;
import java.util.Map;

import static java.lang.Thread.sleep;

public class TestEdenResize {
    @Test
    public void TestResizingEden() {
        Cache<Integer, Integer> cache = Caffeine.newBuilder().maximumSize(1024).build();
        cache.put(1, 1);
        Policy.Eviction<Integer, Integer> eviction = cache.policy().eviction().orElse(null);
        Assert.assertNotNull(cache.getIfPresent(1));
        Assert.assertTrue("Failed to set percentMain to 0.5", cleanlySetPercentMain(eviction, 0.5d));
        Assert.assertNotNull(cache.getIfPresent(1));
        Assert.assertTrue("Failed to set percentMain to 0", cleanlySetPercentMain(eviction, 0d));
        Assert.assertNotNull(cache.getIfPresent(1));
        Assert.assertTrue("Failed to set percentMain to 1", cleanlySetPercentMain(eviction, 1d));
        Assert.assertNotNull(cache.getIfPresent(1));
    }

    private boolean cleanlySetPercentMain(Policy.Eviction<Integer, Integer> eviction, double percentMain) {
        Assert.assertNotNull(eviction);
        try {
            eviction.setMaximum(eviction.getMaximum(), percentMain);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Test
    public void TestPercentageMainOutOfBounds() {
        Cache<Integer, Integer> cache = Caffeine.newBuilder().maximumSize(1024).build();
        Policy.Eviction<Integer, Integer> eviction = cache.policy().eviction().orElse(null);
        Assert.assertFalse("Failed to throw for percentMain < 0", cleanlySetPercentMain(eviction, -1d));
        Assert.assertFalse("Failed to throw for percentMain > 1", cleanlySetPercentMain(eviction, 1.01d));
    }

    @Test
    public void Test100PercentWindow() throws InterruptedException {
        Cache<Integer, Integer> cache = Caffeine.newBuilder().maximumSize(1000).build();
        Policy.Eviction<Integer, Integer> eviction = cache.policy().eviction().get();
        eviction.setMaximum(eviction.getMaximum(), 0d);
        for (int counter = 0; counter < 2000; ++counter) {
            cache.put(counter, counter);
        }

        // need to wait for a moment to give the cache time to evict stuff.
        sleep(1000);

        // if the window is 100% of the cache, the last 1000 entries should be in the cache. There can be a  temporary
        // overhang containing arbitrary older keys.
        // however, it turns out that about 10% are still older keys
        Map<Integer, Integer> cm = cache.asMap();
        for (int counter = 1200; counter < 2000; ++counter) {
            Integer val = cache.getIfPresent(counter);
            Assert.assertNotNull(String.format("Failed to find key %d", counter), val);
        }
    }

    @Test
    public void Test50PercentWindow() throws InterruptedException {
        Cache<Integer, Integer> cache = Caffeine.newBuilder().maximumSize(1000).build();
        Policy.Eviction<Integer, Integer> eviction = cache.policy().eviction().get();
        eviction.setMaximum(eviction.getMaximum(), .5d);
        for (int counter = 0; counter < 2000; ++counter) {
            cache.put(counter, counter);
        }

        // need to wait for a moment to give the cache time to evict stuff.
        sleep(1000);

        // if the window is 50% of the cache, the last 500 entries should be in the cache.
        Map<Integer, Integer> cm = cache.asMap();
        for (int counter = 1500; counter < 2000; ++counter) {
            Assert.assertTrue(cm.keySet().contains(counter));
        }

        // some keys between 1000 and 1500 will be missing because they didn't make it to the main cache
        boolean missing = false;
        for (int counter = 1000; counter < 1500; ++counter) {
            if (!cm.keySet().contains(counter)) {
                missing = true;
            }
        }
        Assert.assertTrue("All keys made it to the main cache!", missing);
    }
}