/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.policy.product;

import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.CacheManagerBuilder;
import org.ehcache.config.CacheConfigurationBuilder;
import org.ehcache.config.Eviction.Prioritizer;
import org.ehcache.config.EvictionPrioritizer;
import org.ehcache.config.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * Ehcache 3 implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Ehcache3Policy implements Policy {
  private final Cache<Object, Object> cache;
  private final PolicyStats policyStats;
  private final int maximumSize;
  private int size;

  public Ehcache3Policy(String name, Config config) {
    policyStats = new PolicyStats(name);
    Ehcache3Settings settings = new Ehcache3Settings(config);
    CacheManager cacheManager = CacheManagerBuilder.newCacheManagerBuilder().build(true);
    cache = cacheManager.createCache(name,
        CacheConfigurationBuilder.newCacheConfigurationBuilder()
            .withResourcePools(ResourcePoolsBuilder.newResourcePoolsBuilder()
                .heap(settings.maximumSize(), EntryUnit.ENTRIES)
                .build())
            .usingEvictionPrioritizer(settings.prioritizer())
            .buildConfig(Object.class, Object.class));
    maximumSize = settings.maximumSize();
  }

  @Override
  public void record(long key) {
    Object value = cache.putIfAbsent(key, key);
    if (value == null) {
      size++;
      policyStats.recordMiss();
      if (size > maximumSize) {
        policyStats.recordEviction();
        size--;
      }
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  static final class Ehcache3Settings extends BasicSettings {
    public Ehcache3Settings(Config config) {
      super(config);
    }
    public EvictionPrioritizer<Object, Object> prioritizer() {
      return Prioritizer.valueOf(config().getString("ehcache3.policy").toUpperCase());
    }
  }
}
