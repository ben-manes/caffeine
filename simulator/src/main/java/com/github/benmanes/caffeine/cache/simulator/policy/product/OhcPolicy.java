/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import org.caffinitas.ohc.CacheSerializer;
import org.caffinitas.ohc.Eviction;
import org.caffinitas.ohc.OHCache;
import org.caffinitas.ohc.OHCacheBuilder;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * Off-Heap-Cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "product.OHC")
public final class OhcPolicy implements KeyOnlyPolicy {
  private static final long ENTRY_SIZE = 80;

  private final OHCache<Long, Long> cache;
  private final PolicyStats policyStats;

  public OhcPolicy(OhcSettings settings, Eviction policy) {
    policyStats = new PolicyStats(name() + " (%s)", (policy == Eviction.LRU) ? "Lru" : "W-TinyLfu");
    cache = OHCacheBuilder.<Long, Long>newBuilder()
        .capacity(ENTRY_SIZE * settings.maximumSize())
        .edenSize(settings.percentEden())
        .valueSerializer(longSerializer)
        .keySerializer(longSerializer)
        .eviction(policy)
        .build();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    OhcSettings settings = new OhcSettings(config);
    return settings.policy().stream()
        .map(policy -> new OhcPolicy(settings, policy))
        .collect(toSet());
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
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    policyStats.addEvictions(cache.stats().getEvictionCount());
    checkState(policyStats.hitCount() == cache.stats().getHitCount());
    checkState(policyStats.missCount() == cache.stats().getMissCount());

    try {
      cache.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static final class OhcSettings extends BasicSettings {
    public OhcSettings(Config config) {
      super(config);
    }
    public double percentEden() {
      return config().getDouble("ohc.percent-eden");
    }
    public Set<Eviction> policy() {
      Set<Eviction> policies = new HashSet<>();
      for (String policy : config().getStringList("ohc.policy")) {
        String name = policy.toLowerCase(US).replaceAll("[^a-z]", "");
        if (name.equals("lru")) {
          policies.add(Eviction.LRU);
        } else if (name.equals("wtinylfu")) {
          policies.add(Eviction.W_TINY_LFU);
        } else {
          throw new IllegalArgumentException("Unknown policy: " + policy);
        }
      }
      return policies;
    }
  }

  static final CacheSerializer<Long> longSerializer = new CacheSerializer<Long>() {
    @Override public void serialize(Long value, ByteBuffer buffer) {
      buffer.putLong(value);
    }
    @Override public Long deserialize(ByteBuffer buffer) {
      return buffer.getLong();
    }
    @Override public int serializedSize(Long value) {
      return Long.BYTES;
    }
  };
}
