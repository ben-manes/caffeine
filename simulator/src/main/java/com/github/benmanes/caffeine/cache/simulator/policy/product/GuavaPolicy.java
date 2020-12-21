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

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.typesafe.config.Config;

/**
 * Guava cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.Guava", characteristics = WEIGHTED)
public final class GuavaPolicy implements Policy {
  private final Cache<Long, AccessEvent> cache;
  private final PolicyStats policyStats;

  public GuavaPolicy(Config config, Set<Characteristic> characteristics) {
    policyStats = new PolicyStats(name());
    BasicSettings settings = new BasicSettings(config);
    CacheBuilder<Long, AccessEvent> builder = CacheBuilder.newBuilder()
        .removalListener(notification -> policyStats.recordEviction());
    if (characteristics.contains(WEIGHTED)) {
      builder.maximumWeight(settings.maximumSize());
      builder.weigher((key, value) -> value.weight());
    } else {
      builder.maximumSize(settings.maximumSize());
    }
    cache = builder.build();
  }

  @Override
  public void record(AccessEvent event) {
    AccessEvent value = cache.getIfPresent(event.key());
    if (value == null) {
      cache.put(event.key(), event);
      policyStats.recordWeightedMiss(event.weight());
    } else {
      policyStats.recordWeightedHit(event.weight());
      if (event.weight() != value.weight()) {
        cache.put(event.key(), event);
      }
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }
}
