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

import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.EnumSet;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.CaseFormat;
import com.trivago.triava.tcache.Cache;
import com.trivago.triava.tcache.EvictionPolicy;
import com.trivago.triava.tcache.TCacheFactory;
import com.typesafe.config.Config;

/**
 * TCache implementation. This library uses high/low watermarks and does not honor a strict bound.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.TCache")
public final class TCachePolicy implements KeyOnlyPolicy {
  private final Cache<Long, Boolean> cache;
  private final PolicyStats policyStats;
  private final TCacheFactory factory;

  public TCachePolicy(TCacheSettings settings, Eviction policy) {
    policyStats = new PolicyStats(name() + " (%s)", policy);
    factory = new TCacheFactory();
    cache = factory.<Long, Boolean>builder()
        .setMaxElements(Math.toIntExact(settings.maximumSize()))
        .setEvictionPolicy(policy.type)
        .setStatistics(true)
        .build();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    var settings = new TCacheSettings(config);
    return settings.policy().stream()
        .map(policy -> new TCachePolicy(settings, policy))
        .collect(toUnmodifiableSet());
  }

  @Override
  public void record(long key) {
    Object value = cache.get(key);
    if (value == null) {
      policyStats.recordMiss();
      cache.put(key, Boolean.TRUE);
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    factory.close();
    policyStats.addEvictions(cache.statistics().getEvictionCount());
  }

  public static final class TCacheSettings extends BasicSettings {
    public TCacheSettings(Config config) {
      super(config);
    }
    public Set<Eviction> policy() {
      var policies = EnumSet.noneOf(Eviction.class);
      for (var policy : config().getStringList("tcache.policy")) {
        if (policy.equalsIgnoreCase("lru")) {
          policies.add(Eviction.LRU);
        } else if (policy.equalsIgnoreCase("lfu")) {
          policies.add(Eviction.LFU);
        } else {
          throw new IllegalArgumentException("Unknown policy: " + policy);
        }
      }
      return policies;
    }
  }

  public enum Eviction {
    LRU(EvictionPolicy.LRU),
    LFU(EvictionPolicy.LFU);

    final EvictionPolicy type;

    Eviction(EvictionPolicy type) {
      this.type = type;
    }
    @Override public String toString() {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, name());
    }
  }
}
