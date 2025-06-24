/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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

import static com.google.common.collect.Sets.toImmutableEnumSet;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.Map;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.CaseFormat;
import com.google.common.base.Enums;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

/**
 * ExpiringMap cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.ExpiringMap")
public final class ExpiringMapPolicy implements Policy {
  private final Map<Long, Boolean> cache;
  private final PolicyStats policyStats;
  private final int maximumSize;

  public ExpiringMapPolicy(ExpiringMapSettings settings, Eviction policy) {
    policyStats = new PolicyStats(name() + " (%s)", policy);
    maximumSize = Math.toIntExact(settings.maximumSize());
    cache = ExpiringMap.builder()
        .expirationPolicy(policy.type)
        .maxSize(maximumSize)
        .build();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    var settings = new ExpiringMapSettings(config);
    return settings.policy().stream()
        .map(policy -> new ExpiringMapPolicy(settings, policy))
        .collect(toUnmodifiableSet());
  }

  @Override
  public void record(AccessEvent event) {
    Long key = event.longKey();
    var value = cache.get(key);
    if (value == null) {
      if (cache.size() == maximumSize) {
        policyStats.recordEviction();
      }
      cache.put(key, true);
      policyStats.recordMiss();
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  public static final class ExpiringMapSettings extends BasicSettings {
    public ExpiringMapSettings(Config config) {
      super(config);
    }
    public ImmutableSet<Eviction> policy() {
      return config().getStringList("expiring-map.policy").stream()
          .map(policy -> Enums.getIfPresent(Eviction.class, policy.toUpperCase(US)).toJavaUtil()
            .orElseThrow(() -> new IllegalArgumentException("Unknown policy: " + policy)))
          .collect(toImmutableEnumSet());
    }
  }

  public enum Eviction {
    FIFO(ExpirationPolicy.CREATED),
    LRU(ExpirationPolicy.ACCESSED);

    final ExpirationPolicy type;

    Eviction(ExpirationPolicy type) {
      this.type = type;
    }
    @Override public String toString() {
      return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, name());
    }
  }
}
