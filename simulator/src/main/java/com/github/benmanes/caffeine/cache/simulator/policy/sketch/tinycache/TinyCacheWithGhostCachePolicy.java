/*
 * Copyright 2015 Gilga Einziger. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.tinycache;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.Characteristics;
import com.github.benmanes.caffeine.cache.simulator.admission.tinycache.TinyCacheWithGhostCache;
import com.github.benmanes.caffeine.cache.simulator.parser.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

/**
 * @author gilga1983@gmail.com (Gil Einziger)
 */
public final class TinyCacheWithGhostCachePolicy implements KeyOnlyPolicy {
  private final TinyCacheWithGhostCache tinyCache;
  private final PolicyStats policyStats;

  public TinyCacheWithGhostCachePolicy(Config config) {
    BasicSettings settings = new BasicSettings(config);
    this.policyStats = new PolicyStats("sketch.TinyCache_GhostCache",settings.traceCharacteristics());
    tinyCache = new TinyCacheWithGhostCache((int) Math.ceil(settings.maximumSize() / 64.0),
        64, settings.randomSeed());
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new TinyCacheWithGhostCachePolicy(config));
  }

  @Override
  public void record(AccessEvent entry) {
    long key = entry.getKey();
    if (tinyCache.contains(key)) {
      tinyCache.recordItem(key);
      policyStats.recordHit(entry);
    } else {
      boolean evicted = tinyCache.addItem(key);
      tinyCache.recordItem(key);
      policyStats.recordMiss(entry);
      if (evicted) {
        policyStats.recordEviction();
      }
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public Set<Characteristics> getCharacteristicsSet() {
    return ImmutableSet.of(Characteristics.KEY);
  }
}
