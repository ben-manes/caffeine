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

import static java.util.Locale.US;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.primitives.Ints;
import com.trivago.triava.tcache.Cache;
import com.trivago.triava.tcache.TCacheFactory;
import com.trivago.triava.tcache.eviction.EvictionInterface;
import com.trivago.triava.tcache.eviction.LFUEviction;
import com.trivago.triava.tcache.eviction.LRUEviction;
import com.typesafe.config.Config;

/**
 * TCache implementation. This library uses high/low watermarks and does not honor a strict bound.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.TCache")
public final class TCachePolicy implements KeyOnlyPolicy {
  private final Cache<Object, Object> cache;
  private final PolicyStats policyStats;

  public TCachePolicy(Config config) {
    policyStats = new PolicyStats(name());
    TCacheSettings settings = new TCacheSettings(config);
    cache = TCacheFactory.standardFactory().builder()
        .setMaxElements(Ints.checkedCast(settings.maximumSize()))
        .setEvictionClass(settings.policy())
        .setStatistics(true)
        .build();
  }

  @Override
  public void record(long key) {
    Object value = cache.get(key);
    if (value == null) {
      policyStats.recordMiss();
      cache.put(key, key);
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
    cache.close();
    policyStats.addEvictions(cache.statistics().getEvictionCount());
  }

  static final class TCacheSettings extends BasicSettings {
    public TCacheSettings(Config config) {
      super(config);
    }
    public <K, V> EvictionInterface<K, V> policy() {
      String policy = config().getString("tcache.policy").toLowerCase(US);
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
