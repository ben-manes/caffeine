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

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.tinycache.TinyCacheWithGhostCache;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

/**
 * @author gilga1983@gmail.com (Gil Einziger)
 */
@PolicySpec(name = "sketch.TinyCache_GhostCache")
public final class TinyCacheWithGhostCachePolicy implements KeyOnlyPolicy {
  private final TinyCacheWithGhostCache tinyCache;
  private final PolicyStats policyStats;

  public TinyCacheWithGhostCachePolicy(Config config) {
    this.policyStats = new PolicyStats(name());
    BasicSettings settings = new BasicSettings(config);
    int maximumSize = Ints.checkedCast(settings.maximumSize());
    tinyCache = new TinyCacheWithGhostCache((int) Math.ceil(maximumSize / 64.0),
        64, settings.randomSeed());
  }

  @Override
  public void record(long key) {
    if (tinyCache.contains(key)) {
      tinyCache.recordItem(key);
      policyStats.recordHit();
    } else {
      boolean evicted = tinyCache.addItem(key);
      tinyCache.recordItem(key);
      policyStats.recordMiss();
      if (evicted) {
        policyStats.recordEviction();
      }
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }
}
