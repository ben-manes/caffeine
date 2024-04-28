/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.EnumSet;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.CaseFormat;
import com.tangosol.net.cache.ConfigurableCacheMap.UnitCalculator;
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
@PolicySpec(name = "product.Coherence", characteristics = WEIGHTED)
public final class CoherencePolicy implements Policy {
  private final PolicyStats policyStats;
  @SuppressWarnings("PMD.LooseCoupling")
  private final LocalCache cache;

  public CoherencePolicy(CoherenceSettings settings, Eviction policy) {
    policyStats = new PolicyStats(name() + " (%s)", policy);

    // auto scale units to integer range (from LocalScheme)
    int factor = 1;
    long maximum = settings.maximumSize();
    while (maximum >= Integer.MAX_VALUE) {
      maximum /= 1024;
      factor *= 1024;
    }

    cache = new LocalCache();
    cache.setUnitFactor(factor);
    cache.setHighUnits((int) maximum);
    cache.setEvictionType(policy.type);
    cache.addMapListener(new CoherenceListener());
    cache.setUnitCalculator(new AccessEventCalculator());
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    var settings = new CoherenceSettings(config);
    return settings.policy().stream()
        .map(policy -> new CoherencePolicy(settings, policy))
        .collect(toUnmodifiableSet());
  }

  @Override
  public void record(AccessEvent event) {
    var value = (AccessEvent) cache.get(event.key());
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

  private final class CoherenceListener implements MapListener<Object, Object> {
    @Override public void entryInserted(MapEvent<Object, Object> event) {}
    @Override public void entryUpdated(MapEvent<Object, Object> event) {}
    @Override public void entryDeleted(MapEvent<Object, Object> event) {
      policyStats.recordEviction();
    }
  }

  private static final class AccessEventCalculator implements UnitCalculator {
    @Override public String getName() {
      return "Coherence";
    }
    @Override public int calculateUnits(Object key, Object value) {
      return ((AccessEvent) value).weight();
    }
  }

  public static final class CoherenceSettings extends BasicSettings {
    public CoherenceSettings(Config config) {
      super(config);
    }
    public Set<Eviction> policy() {
      var policies = EnumSet.noneOf(Eviction.class);
      for (var policy : config().getStringList("coherence.policy")) {
        if (policy.equalsIgnoreCase("hybrid")) {
          policies.add(Eviction.HYBRID);
        } else if (policy.equalsIgnoreCase("lfu")) {
          policies.add(Eviction.LFU);
        } else if (policy.equalsIgnoreCase("lru")) {
          policies.add(Eviction.LRU);
        } else {
          throw new IllegalArgumentException("Unknown policy: " + policy);
        }
      }
      return policies;
    }
  }

  @SuppressWarnings("deprecation")
  public enum Eviction {
    HYBRID(LocalCache.EVICTION_POLICY_HYBRID),
    LRU(LocalCache.EVICTION_POLICY_LRU),
    LFU(LocalCache.EVICTION_POLICY_LFU);

    final int type;

    Eviction(int type) {
      this.type = type;
    }
    @Override public String toString() {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, name());
    }
  }
}
