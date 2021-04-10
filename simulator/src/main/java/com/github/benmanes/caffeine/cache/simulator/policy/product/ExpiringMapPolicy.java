/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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
import com.typesafe.config.Config;

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

/**
 * ExpiringMap cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.ExpiringMap")
public final class ExpiringMapPolicy implements KeyOnlyPolicy {
  private final ExpiringMap<Object, Object> cache;
  private final PolicyStats policyStats;

  public ExpiringMapPolicy(Config config) {
    ExpiringMapSettings settings = new ExpiringMapSettings(config);
    policyStats = new PolicyStats(name());
    cache = ExpiringMap.builder()
        .maxSize(Ints.checkedCast(settings.maximumSize()))
        .expirationPolicy(settings.policy())
        .build();
  }

  @Override
  public void record(long key) {
    Object value = cache.get(key);
    if (value == null) {
      if (cache.size() == cache.getMaxSize()) {
        policyStats.recordEviction();
      }
      cache.put(key, key);
      policyStats.recordMiss();
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  static final class ExpiringMapSettings extends BasicSettings {
    public ExpiringMapSettings(Config config) {
      super(config);
    }
    public ExpirationPolicy policy() {
      String policy = config().getString("expiring-map.policy").toLowerCase(US);
      switch (policy) {
        case "fifo":
          return ExpirationPolicy.CREATED;
        case "lru":
          return ExpirationPolicy.ACCESSED;
        default:
          throw new IllegalArgumentException("Unknown policy type: " + policy);
      }
    }
  }
}
