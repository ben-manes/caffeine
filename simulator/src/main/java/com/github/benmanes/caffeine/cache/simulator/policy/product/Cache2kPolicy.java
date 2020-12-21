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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.event.CacheEntryEvictedListener;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * Cache2k implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.Cache2k", characteristics = WEIGHTED)
public final class Cache2kPolicy implements Policy {
  private static final Logger logger = Logger.getLogger("org.cache2k");

  private final Cache<Long, AccessEvent> cache;
  private final PolicyStats policyStats;

  public Cache2kPolicy(Config config, Set<Characteristic> characteristics) {
    logger.setLevel(Level.WARNING);

    policyStats = new PolicyStats(name());
    CacheEntryEvictedListener<Long, AccessEvent> listener =
        (cache, entry) -> policyStats.recordEviction();
    BasicSettings settings = new BasicSettings(config);
    Cache2kBuilder<Long, AccessEvent> builder = Cache2kBuilder.of(Long.class, AccessEvent.class)
        .addListener(listener)
        .strictEviction(true);
    if (characteristics.contains(WEIGHTED)) {
      builder.weigher((Long key, AccessEvent value) -> value.weight());
      builder.maximumWeight(settings.maximumSize());
    } else {
      builder.entryCapacity(settings.maximumSize());
    }
    cache = builder.build();
  }

  @Override
  public void record(AccessEvent event) {
    AccessEvent value = cache.peek(event.key());
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
    cache.close();
  }
}
