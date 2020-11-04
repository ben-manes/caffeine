/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
import static com.google.common.base.Preconditions.checkState;

import java.util.Set;

import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.Cache.CacheStats;
import org.elasticsearch.common.cache.CacheBuilder;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

/**
 * ElasticSearch's LRU cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ElasticSearchPolicy implements Policy {
  private final Cache<Long, AccessEvent> cache;
  private final PolicyStats policyStats;

  public ElasticSearchPolicy(Config config, Set<Characteristic> characteristics) {
    policyStats = new PolicyStats("product.ElasticSearch");
    BasicSettings settings = new BasicSettings(config);
    CacheBuilder<Long, AccessEvent> builder = CacheBuilder.<Long, AccessEvent>builder()
        .removalListener(notification -> policyStats.recordEviction())
        .setMaximumWeight(settings.maximumSize());
    if (characteristics.contains(WEIGHTED)) {
      builder.weigher((key, value) -> value.weight());
    }
    cache = builder.build();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config, Set<Characteristic> characteristics) {
    return ImmutableSet.of(new ElasticSearchPolicy(config, characteristics));
  }

  @Override public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }

  @Override
  public void record(AccessEvent event) {
    AccessEvent value = cache.get(event.key());
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

  @Override
  public void finished() {
    CacheStats stats = cache.stats();
    checkState(policyStats.hitCount() == stats.getHits());
    checkState(policyStats.missCount() == stats.getMisses());
    checkState(policyStats.evictionCount() == stats.getEvictions());
  }
}
