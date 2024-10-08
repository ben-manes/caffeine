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
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * @author gilga1983@gmail.com (Gil Einziger)
 */
@PolicySpec(name = "sketch.TinyCache")
public final class TinyCachePolicy implements KeyOnlyPolicy {
  private final PolicyStats policyStats;
  private final TinyCache tinyCache;

  public TinyCachePolicy(Config config) {
    var settings = new BasicSettings(config);
    this.policyStats = new PolicyStats(name());
    int maximumSize = Math.toIntExact(settings.maximumSize());
    tinyCache = new TinyCache((int) Math.ceil(maximumSize / 64.0),
        64, settings.randomSeed());
  }

  @Override
  public void record(long key) {
    if (tinyCache.contains(key)) {
      policyStats.recordHit();
    } else {
      boolean evicted = tinyCache.addItem(key);
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
