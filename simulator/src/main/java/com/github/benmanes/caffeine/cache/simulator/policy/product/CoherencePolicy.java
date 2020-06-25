/*
 * Copyright 2020 Ben Manes. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.tangosol.net.cache.LocalCache;
import com.tangosol.util.MapEvent;
import com.tangosol.util.MapListener;
import com.typesafe.config.Config;

/**
 * Coherence cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("deprecation")
public final class CoherencePolicy implements KeyOnlyPolicy {
  private final PolicyStats policyStats;
  private final LocalCache cache;

  public CoherencePolicy(Config config) {
    policyStats = new PolicyStats("product.Coherence");
    BasicSettings settings = new BasicSettings(config);
    cache = new LocalCache(settings.maximumSize());
    cache.addMapListener(new MapListener<Object, Object>() {
      @Override public void entryInserted(MapEvent<Object, Object> event) {}
      @Override public void entryUpdated(MapEvent<Object, Object> event) {}
      @Override public void entryDeleted(MapEvent<Object, Object> event) {
        policyStats.recordEviction();
      }
    });
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new CoherencePolicy(config));
  }

  @Override
  public void record(long key) {
    Object value = cache.get(key);
    if (value == null) {
      cache.put(key, key);
      policyStats.recordMiss();
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public void finished() {
    checkState(policyStats.hitCount() == cache.getCacheHits());
    checkState(policyStats.missCount() == cache.getCacheMisses());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }
}
