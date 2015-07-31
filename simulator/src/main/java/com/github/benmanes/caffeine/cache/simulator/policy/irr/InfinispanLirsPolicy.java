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

import java.util.Map;

import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.commons.util.concurrent.jdk8backported.BoundedEquivalentConcurrentHashMapV8;
import org.infinispan.commons.util.concurrent.jdk8backported.BoundedEquivalentConcurrentHashMapV8.Eviction;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * The LIRS algorithm, as implemented by Infinispan.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class InfinispanLirsPolicy implements Policy {
  private final PolicyStats policyStats;
  private final Map<Object, Object> map;
  private final int maximumSize;

  public InfinispanLirsPolicy(String name, Config config) {
    policyStats = new PolicyStats(name);
    maximumSize = new BasicSettings(config).maximumSize();
    map = new BoundedEquivalentConcurrentHashMapV8<>(maximumSize, Eviction.LIRS,
        BoundedEquivalentConcurrentHashMapV8.getNullEvictionListener(),
        AnyEquivalence.getInstance(), AnyEquivalence.getInstance());
  }

  @Override
  public void record(Comparable<Object> key) {
    Object value = map.get(key);
    if (value == null) {
      if (map.size() >= maximumSize) {
        policyStats.recordEviction();
      }
      map.put(key, key);
      policyStats.recordMiss();
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }
}
