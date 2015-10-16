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

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.trivago.triava.tcache.JamPolicy;
import com.trivago.triava.tcache.TCacheFactory;
import com.trivago.triava.tcache.core.Builder;
import com.trivago.triava.tcache.core.EvictionInterface;
import com.trivago.triava.tcache.eviction.CacheLimit;
import com.trivago.triava.tcache.eviction.LFUEviction;
import com.trivago.triava.tcache.eviction.LRUEviction;
import com.typesafe.config.Config;

/**
 * TCache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TCachePolicy implements Policy {
  private final SyncCache<Object, Object> cache;
  private final PolicyStats policyStats;
  private final int maximumSize;

  public TCachePolicy(String name, Config config) {
    TCacheSettings settings = new TCacheSettings(config);
    maximumSize = settings.maximumSize();
    policyStats = new PolicyStats(name);
    cache = new SyncCache<>(TCacheFactory.standardFactory().builder()
        .setEvictionClass(settings.policy())
        .setExpectedMapSize(maximumSize)
        .setJamPolicy(JamPolicy.DROP));
  }

  @Override
  public void record(long key) {
    while (!cache.ensureFreeCapacity() || (cache.size() > maximumSize)) {
      // spin
    }
    Object value = cache.get(key);
    if (value == null) {
      policyStats.recordMiss();
      if (cache.size() == maximumSize) {
        policyStats.recordEviction();
      }
      cache.put(key, key);
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  static final class SyncCache<K, V> extends CacheLimit<K, V> {
    public SyncCache(Builder<K, V> builder) {
      super(builder);
    }
    @Override public boolean ensureFreeCapacity() {
      return super.ensureFreeCapacity();
    }
  }

  static final class TCacheSettings extends BasicSettings {
    public TCacheSettings(Config config) {
      super(config);
    }
    public <K, V> EvictionInterface<K, V> policy() {
      String policy = config().getString("tcache.policy").toLowerCase();
      switch (policy) {
        case "lfu":
          return new LFUEviction<>();
        case "lru":
          return new LRUEviction<>();
        default:
          throw new IllegalArgumentException("Unknown policy type: " + policy);
      }
    }
  }
}
