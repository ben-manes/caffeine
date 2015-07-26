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
package com.github.benmanes.caffeine.cache.simulator.policy.irr;

import org.apache.jackrabbit.oak.cache.CacheLIRS;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.typesafe.config.Config;

/**
 * An approximation of the the LIRS replacement algorithm invented by Xiaodong Zhang and Song Jiang
 * as described in http://www.cse.ohio-state.edu/~zhang/lirs-sigmetrics-02.html with a few smaller
 * changes: An additional queue for non-resident entries is used, to prevent unbound memory usage.
 * The maximum size of this queue is at most the size of the rest of the stack. About 6.25% of the
 * mapped entries are cold.
 * <p>
 * Accessed entries are only moved to the top of the stack if at least a number of other entries
 * have been moved to the front (1% by default). Write access and moving entries to the top of the
 * stack is synchronized per segment.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JackrabbitLirsPolicy implements Policy {
  private final ThreadLocal<Object> computed = new ThreadLocal<>();
  private final LoadingCache<Object, Object> cache;
  private final PolicyStats policyStats;

  public JackrabbitLirsPolicy(String name, Config config) {
    LirsSettings settings = new LirsSettings(config);
    this.policyStats = new PolicyStats(name);
    this.cache = new CacheLIRS.Builder<>()
        .recordStats()
        .segmentCount(1)
        .maximumSize(settings.maximumSize())
        .stackMoveDistance(settings.stackMoveDistance())
        .build(new CacheLoader<Object, Object>() {
          @Override public Object load(Object key) {
            computed.set(key);
            return key;
          }
        });
  }

  @Override
  public PolicyStats stats() {
    policyStats.setEvictionCount(cache.stats().evictionCount());
    return policyStats;
  }

  @Override
  public void record(Comparable<Object> key) {
    cache.getUnchecked(key);
    if (computed.get() == null) {
      policyStats.recordHit();
    } else {
      computed.set(null);
      policyStats.recordMiss();
    }
  }
}
