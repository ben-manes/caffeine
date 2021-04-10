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
package com.github.benmanes.caffeine.cache.simulator.policy.opt;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.primitives.Ints;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * A cache that has no maximum size. This demonstrates the upper bound of the hit rate due to
 * compulsory misses (first reference misses), which can only be avoided if the application can
 * intelligently prefetch the data prior to the request.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "opt.Unbounded", characteristics = WEIGHTED)
public final class UnboundedPolicy implements Policy {
  private final PolicyStats policyStats;
  private final LongOpenHashSet data;

  public UnboundedPolicy(Config config, Set<Characteristic> characteristics) {
    BasicSettings settings = new BasicSettings(config);
    int initialSize = characteristics.contains(WEIGHTED)
        ? LongOpenHashSet.DEFAULT_INITIAL_SIZE
        : Ints.saturatedCast(settings.maximumSize());
    data = new LongOpenHashSet(initialSize);
    policyStats = new PolicyStats(name());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(AccessEvent event) {
    policyStats.recordOperation();
    if (data.add(event.key())) {
      policyStats.recordWeightedMiss(event.weight());
    } else {
      policyStats.recordWeightedHit(event.weight());
    }
  }
}
