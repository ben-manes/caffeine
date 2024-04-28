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

import static com.google.common.base.Preconditions.checkState;
import static com.hazelcast.config.MaxSizePolicy.ENTRY_COUNT;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.EnumSet;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.CaseFormat;
import com.google.common.base.Enums;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.ManagedContext;
import com.hazelcast.internal.nearcache.NearCache;
import com.hazelcast.internal.nearcache.impl.DefaultNearCache;
import com.hazelcast.internal.serialization.Data;
import com.hazelcast.internal.serialization.SerializationService;
import com.hazelcast.partition.PartitioningStrategy;
import com.typesafe.config.Config;

/**
 * Hazelcast cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.Hazelcast")
public final class HazelcastPolicy implements KeyOnlyPolicy {
  private final NearCache<Long, Boolean> cache;
  private final PolicyStats policyStats;
  private final int maximumSize;

  public HazelcastPolicy(HazelcastSettings settings, EvictionPolicy policy) {
    policyStats = new PolicyStats(name() + " (%s)",
        CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, policy.name()));
    maximumSize = Math.toIntExact(settings.maximumSize());
    var config = new NearCacheConfig()
        .setSerializeKeys(false)
        .setInMemoryFormat(InMemoryFormat.OBJECT)
        .setEvictionConfig(new EvictionConfig()
            .setMaxSizePolicy(ENTRY_COUNT)
            .setEvictionPolicy(policy)
            .setSize(maximumSize));
    cache = new DefaultNearCache<>("simulation", config, DummySerializationService.INSTANCE,
        /* TaskScheduler */ null, getClass().getClassLoader(), /* HazelcastProperties */ null);
    cache.initialize();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    var settings = new HazelcastSettings(config);
    return settings.policy().stream()
        .map(policy -> new HazelcastPolicy(settings, policy))
        .collect(toUnmodifiableSet());
  }

  @Override
  public void record(long key) {
    Object value = cache.get(key);
    if (value == null) {
      if (cache.size() == maximumSize) {
        policyStats.recordEviction();
      }
      cache.put(key, /* keyData */ null, Boolean.TRUE, /* valueDate */ null);
      policyStats.recordMiss();
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
    var stats = cache.getNearCacheStats();
    cache.destroy();

    checkState(stats.getOwnedEntryCount() <= maximumSize);
    checkState(stats.getHits() == policyStats.hitCount());
    checkState(stats.getMisses() == policyStats.missCount());
    checkState(stats.getEvictions() == policyStats.evictionCount());
  }

  public static final class HazelcastSettings extends BasicSettings {
    public HazelcastSettings(Config config) {
      super(config);
    }
    public Set<EvictionPolicy> policy() {
      var policies = EnumSet.noneOf(EvictionPolicy.class);
      for (var policy : config().getStringList("hazelcast.policy")) {
        var option = Enums.getIfPresent(EvictionPolicy.class, policy.toUpperCase(US)).toJavaUtil();
        option.ifPresentOrElse(policies::add, () -> {
          throw new IllegalArgumentException("Unknown policy: " + policy);
        });
      }
      return policies;
    }
  }

  @SuppressWarnings({"rawtypes", "TypeParameterUnusedInFormals", "unchecked"})
  enum DummySerializationService implements SerializationService {
    INSTANCE;

    @Override public <B extends Data> B toData(Object obj) {
      return (B) obj;
    }
    @Override public <B extends Data> B toDataWithSchema(Object obj) {
      return (B) obj;
    }
    @Override public <B extends Data> B toData(Object obj, PartitioningStrategy strategy) {
      return (B) obj;
    }
    @Override public <T> T toObject(Object data) {
      return (T) data;
    }
    @Override public <T> T toObject(Object data, Class klazz) {
      return (T) data;
    }
    @Override public ManagedContext getManagedContext() {
      return null;
    }
    @Override public <B extends Data> B trimSchema(Data data) {
      return (B) data;
    }
  }
}
