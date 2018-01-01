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

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

/**
 * Cache2k implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Cache2kPolicy implements Policy {
  private static final Logger logger = Logger.getLogger("org.cache2k");

  private final Cache<Object, Object> cache;
  private final PolicyStats policyStats;
  private final int maximumSize;

  public Cache2kPolicy(Config config) {
    logger.setLevel(Level.WARNING);

    policyStats = new PolicyStats("product.Cache2k");
    BasicSettings settings = new BasicSettings(config);
    cache = Cache2kBuilder.of(Object.class, Object.class)
        .entryCapacity(settings.maximumSize())
        .eternal(true)
        .build();
    maximumSize = settings.maximumSize();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new Cache2kPolicy(config));
  }

  @Override
  public void record(long key) {
    Object value = cache.peek(key);
    if (value == null) {
      policyStats.recordMiss();
      if (cache.asMap().size() == maximumSize) {
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
}
