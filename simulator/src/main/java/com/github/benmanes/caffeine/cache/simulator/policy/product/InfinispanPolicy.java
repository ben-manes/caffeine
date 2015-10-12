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

import java.util.Map;

import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.commons.util.concurrent.jdk8backported.BoundedEquivalentConcurrentHashMapV8;
import org.infinispan.commons.util.concurrent.jdk8backported.BoundedEquivalentConcurrentHashMapV8.Eviction;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * Infinispan cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class InfinispanPolicy implements Policy {
  private final Map<Object, Object> cache;
  private final PolicyStats policyStats;
  private final int maximumSize;

  public InfinispanPolicy(String name, Config config) {
    policyStats = new PolicyStats(name);
    InfinispanSettings settings = new InfinispanSettings(config);
    cache = new BoundedEquivalentConcurrentHashMapV8<>(settings.maximumSize(), settings.policy(),
        BoundedEquivalentConcurrentHashMapV8.getNullEvictionListener(),
        AnyEquivalence.getInstance(), AnyEquivalence.getInstance());
    maximumSize = settings.maximumSize();
  }

  @Override
  public void record(long key) {
    Object value = cache.get(key);
    if (value == null) {
      policyStats.recordMiss();
      if (cache.size() == maximumSize) {
        policyStats.recordEviction();
      }
      cache.put(key, key);
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  static final class InfinispanSettings extends BasicSettings {
    public InfinispanSettings(Config config) {
      super(config);
    }
    public Eviction policy() {
      return Eviction.valueOf(config().getString("infinispan.policy").toUpperCase());
    }
  }
}
