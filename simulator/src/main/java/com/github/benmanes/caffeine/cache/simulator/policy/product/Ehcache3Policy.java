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
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * Ehcache 3 implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.Ehcache3")
public final class Ehcache3Policy implements KeyOnlyPolicy {
  private final Cache<Object, Object> cache;
  private final CacheManager cacheManager;
  private final PolicyStats policyStats;
  private final long maximumSize;

  private long size;

  @SuppressWarnings("PMD.CloseResource")
  public Ehcache3Policy(Config config) {
    policyStats = new PolicyStats(name());
    BasicSettings settings = new BasicSettings(config);
    cacheManager = CacheManagerBuilder.newCacheManagerBuilder().build(true);
    cache = cacheManager.createCache("ehcache3",
        CacheConfigurationBuilder.newCacheConfigurationBuilder(Object.class, Object.class,
            ResourcePoolsBuilder.newResourcePoolsBuilder()
                .heap(settings.maximumSize(), EntryUnit.ENTRIES))
            .build());
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

  @Override
  public void finished() {
    cacheManager.close();
  }
}
