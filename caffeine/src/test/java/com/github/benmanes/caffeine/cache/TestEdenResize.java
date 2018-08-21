package com.github.benmanes.caffeine.cache;

import org.junit.Assert;
import org.junit.Test;

public class TestEdenResize {
    @Test
    public void TestResizingEden(){
        Cache<Integer, Integer> cache = Caffeine.newBuilder().maximumSize(1024).build();
        cache.put(1,1);
        Policy.Eviction<Integer, Integer> eviction = cache.policy().eviction().orElse(null);
        Assert.assertNotNull(eviction);
        Assert.assertNotNull(cache.getIfPresent(1));
        if (eviction != null){
            eviction.setMaximum(eviction.getMaximum(), .5d);
        }
        Assert.assertNotNull(cache.getIfPresent(1));
    }
}
