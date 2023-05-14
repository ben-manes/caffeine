/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.jackrabbit.oak.cache.CacheLIRS;
import org.jctools.maps.NonBlockingHashMap;

import com.github.benmanes.caffeine.cache.impl.Cache2k;
import com.github.benmanes.caffeine.cache.impl.CaffeineCache;
import com.github.benmanes.caffeine.cache.impl.CoherenceCache;
import com.github.benmanes.caffeine.cache.impl.ConcurrentHashMapV7;
import com.github.benmanes.caffeine.cache.impl.ConcurrentMapCache;
import com.github.benmanes.caffeine.cache.impl.Ehcache3;
import com.github.benmanes.caffeine.cache.impl.GuavaCache;
import com.github.benmanes.caffeine.cache.impl.HazelcastCache;
import com.github.benmanes.caffeine.cache.impl.LinkedHashMapCache;
import com.github.benmanes.caffeine.cache.impl.TCache;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.tangosol.net.cache.LocalCache;
import com.trivago.triava.tcache.EvictionPolicy;

import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

/**
 * A factory for creating a {@link BasicCache} implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum CacheType {

  /* --------------- Unbounded --------------- */

  ConcurrentHashMapV7 { // see OpenJDK/7u40-b43
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(
          new ConcurrentHashMapV7<>(maximumSize, 0.75f, CONCURRENCY_LEVEL));
    }
  },
  ConcurrentHashMap {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(new ConcurrentHashMap<>(maximumSize));
    }
  },
  NonBlockingHashMap {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      // Note that writes that update an entry to the same reference are short-circuited
      // and do not mutate the hash table. This causes those writes to be equivalent to a read.
      return new ConcurrentMapCache<>(new NonBlockingHashMap<>(maximumSize));
    }
  },

  /* --------------- Bounded --------------- */

  Cache2k {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new Cache2k<>(maximumSize);
    }
  },
  Caffeine {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new CaffeineCache<>(maximumSize);
    }
  },
  Coherence_Lru {
    @SuppressWarnings("deprecation")
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new CoherenceCache<>(maximumSize, LocalCache.EVICTION_POLICY_LRU);
    }
  },
  Coherence_Lfu {
    @SuppressWarnings("deprecation")
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new CoherenceCache<>(maximumSize, LocalCache.EVICTION_POLICY_LFU);
    }
  },
  Coherence_Hybrid {
    @SuppressWarnings("deprecation")
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new CoherenceCache<>(maximumSize, LocalCache.EVICTION_POLICY_HYBRID);
    }
  },
  ConcurrentLinkedHashMap {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(
          new ConcurrentLinkedHashMap.Builder<K, V>()
            .maximumWeightedCapacity(maximumSize)
            .initialCapacity(maximumSize)
            .build());
    }
  },
  Ehcache3 {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new Ehcache3<>(maximumSize);
    }
  },
  ExpiringMap_Fifo {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(ExpiringMap.builder()
          .expirationPolicy(ExpirationPolicy.CREATED)
          .maxSize(maximumSize)
          .build());
    }
  },
  ExpiringMap_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(ExpiringMap.builder()
          .expirationPolicy(ExpirationPolicy.ACCESSED)
          .maxSize(maximumSize)
          .build());
    }
  },
  Guava {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new GuavaCache<>(maximumSize);
    }
  },
  Hazelcast_Lfu {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new HazelcastCache<>(maximumSize, com.hazelcast.config.EvictionPolicy.LFU);
    }
  },
  Hazelcast_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new HazelcastCache<>(maximumSize, com.hazelcast.config.EvictionPolicy.LRU);
    }
  },
  Hazelcast_Random {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new HazelcastCache<>(maximumSize, com.hazelcast.config.EvictionPolicy.RANDOM);
    }
  },
  Jackrabbit {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(CacheLIRS.<K, V>newBuilder()
          .segmentCount(CONCURRENCY_LEVEL)
          .maximumSize(maximumSize)
          .build().asMap());
    }
  },
  LinkedHashMap_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new LinkedHashMapCache<>(maximumSize, /* accessOrder */ true);
    }
  },
  TCache_Lfu {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new TCache<>(maximumSize, EvictionPolicy.LFU);
    }
  },
  TCache_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new TCache<>(maximumSize, EvictionPolicy.LRU);
    }
  };

  /** The number of hash table segments. */
  public static final int CONCURRENCY_LEVEL = 64;

  /**
   * Creates the cache with the maximum size. Note that some implementations may evict prior to
   * this threshold, and it is the caller's responsibility to adjust accordingly.
   */
  public abstract <K, V> BasicCache<K, V> create(int maximumSize);
}
