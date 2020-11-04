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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

/**
 * Caffeine cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeinePolicy implements Policy {
  private final Cache<Long, AccessEvent> cache;
  private final PolicyStats policyStats;

  public CaffeinePolicy(Config config, Set<Characteristic> characteristics) {
    policyStats = new PolicyStats("product.Caffeine");
    BasicSettings settings = new BasicSettings(config);
    Caffeine<Long, AccessEvent> builder = Caffeine.newBuilder()
        .removalListener((Long key, AccessEvent value, RemovalCause cause) ->
            policyStats.recordEviction())
        .executor(Runnable::run);
    if (characteristics.contains(WEIGHTED)) {
      builder.maximumWeight(settings.maximumSize());
      builder.weigher((key, value) -> value.weight());
    } else {
      builder.maximumSize(settings.maximumSize());
      builder.initialCapacity(Ints.saturatedCast(settings.maximumSize()));
    }
    cache = builder.build();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config, Set<Characteristic> characteristics) {
    return ImmutableSet.of(new CaffeinePolicy(config, characteristics));
  }

  @Override public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
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
