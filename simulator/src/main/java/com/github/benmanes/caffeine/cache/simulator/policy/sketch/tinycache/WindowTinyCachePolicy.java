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
import com.github.benmanes.caffeine.cache.simulator.admission.tinycache.TinyCache;
import com.github.benmanes.caffeine.cache.simulator.admission.tinycache.TinyCacheWithGhostCache;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

public final class WindowTinyCachePolicy implements Policy {
  private final TinyCache window;
  private final PolicyStats policyStats;
  private final TinyCacheWithGhostCache tinyCache;

  public WindowTinyCachePolicy(String name, Config config) {
    BasicSettings settings = new BasicSettings(config);
    policyStats = new PolicyStats(name);
    int maxSize = settings.maximumSize();
    window = new TinyCache(1, 64, 0);
    maxSize -= 64;
    tinyCache = new TinyCacheWithGhostCache((int) Math.ceil(maxSize / 64.0),
        64, settings.randomSeed());
  }

  @Override
  public void record(Comparable<Object> key) {
    if (tinyCache.contains(key.hashCode()) || window.contains(key.hashCode())) {
      tinyCache.recordItem(key.hashCode());
      policyStats.recordHit();
    } else {
      boolean evicted = tinyCache.addItem(key.hashCode());
      if (!evicted) {
        evicted = window.addItem(key.hashCode());
      }
      tinyCache.recordItem(key.hashCode());
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
