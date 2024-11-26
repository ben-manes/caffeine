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

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.tinycache.TinyCache;
import com.github.benmanes.caffeine.cache.simulator.admission.tinycache.TinyCacheWithGhostCache;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;

/**
 * @author gilga1983@gmail.com (Gil Einziger)
 */
@PolicySpec(name = "sketch.WindowTinyCache")
public final class WindowTinyCachePolicy implements KeyOnlyPolicy {
  private final TinyCacheWithGhostCache tinyCache;
  private final @Nullable TinyCache window;
  private final PolicyStats policyStats;

  public WindowTinyCachePolicy(Config config) {
    policyStats = new PolicyStats(name());
    var settings = new BasicSettings(config);
    @Var int maxSize = Math.toIntExact(settings.maximumSize());
    if (maxSize <= 64) {
      window = null;
    } else {
      maxSize -= 64;
      window = new TinyCache(1, 64, 0);
    }
    tinyCache = new TinyCacheWithGhostCache(
        (int) Math.ceil(maxSize / 64.0), 64, settings.randomSeed());
  }

  @Override
  public void record(long key) {
    if (tinyCache.contains(key) || ((window != null) && window.contains(key))) {
      tinyCache.recordItem(key);
      policyStats.recordHit();
    } else {
      @Var boolean evicted = tinyCache.addItem(key);
      if (!evicted && (window != null)) {
        evicted = window.addItem(key);
      }
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
