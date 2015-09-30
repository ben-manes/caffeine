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

import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

/**
 * Ehcache 2 implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Ehcache2Policy implements Policy {
  private static final AtomicInteger counter = new AtomicInteger();
  private static final CacheManager cacheManager = new CacheManager();

  private final PolicyStats policyStats;
  private final int maximumSize;
  private final Ehcache cache;

  public Ehcache2Policy(String name, Config config) {
    Ehcache2Settings settings = new Ehcache2Settings(config);
    maximumSize = settings.maximumSize();
    policyStats = new PolicyStats(name);

    CacheConfiguration configuration = new CacheConfiguration(
        "cache-" + counter.incrementAndGet(), maximumSize);
    configuration.setMemoryStoreEvictionPolicyFromObject(settings.policy());
    cache = new Cache(configuration);
    cacheManager.addCache(cache);
  }

  @Override
  public void record(Comparable<Object> key) {
    Object value = cache.get(key);
    if (value == null) {
      policyStats.recordMiss();
      if (cache.getSize() == maximumSize) {
        policyStats.recordEviction();
      }
      cache.put(new Element(key, key));
    } else {
      policyStats.recordHit();
    }
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  static final class Ehcache2Settings extends BasicSettings {
    public Ehcache2Settings(Config config) {
      super(config);
    }
    public MemoryStoreEvictionPolicy policy() {
      return MemoryStoreEvictionPolicy.fromString(config().getString("ehcache2.policy"));
    }
  }
}
