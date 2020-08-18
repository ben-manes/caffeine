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
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

/**
 * Guava cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class GuavaPolicy implements Policy {
  private final Cache<Long, AccessEvent> cache;
  private final PolicyStats policyStats;

  public GuavaPolicy(Config config) {
    policyStats = new PolicyStats("product.Guava");
    BasicSettings settings = new BasicSettings(config);
    cache = CacheBuilder.newBuilder()
        .maximumWeight(settings.maximumSize())
        .weigher((Long key, AccessEvent value) -> value.weight())
        .removalListener(notification -> policyStats.recordEviction())
        .build();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new GuavaPolicy(config));
  }

  @Override public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }

  @Override
  public void record(AccessEvent event) {
    Object value = cache.getIfPresent(event.key());
    if (value == null) {
      cache.put(event.key(), event);
      policyStats.recordWeightedMiss(event.weight());
    } else {
      policyStats.recordWeightedHit(event.weight());
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }
}
