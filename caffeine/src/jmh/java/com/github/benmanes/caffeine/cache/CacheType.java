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
import org.cache2k.impl.ClockProPlusCache;
import org.cache2k.impl.LruCache;
import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.ehcache.config.Eviction.Prioritizer;
import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.commons.util.concurrent.jdk8backported.BoundedEquivalentConcurrentHashMapV8;
import org.infinispan.commons.util.concurrent.jdk8backported.BoundedEquivalentConcurrentHashMapV8.Eviction;
import org.infinispan.util.concurrent.BoundedConcurrentHashMap;

import com.github.benmanes.caffeine.cache.impl.Cache2k;
import com.github.benmanes.caffeine.cache.impl.CaffeineCache;
import com.github.benmanes.caffeine.cache.impl.ConcurrentHashMapV7;
import com.github.benmanes.caffeine.cache.impl.ConcurrentMapCache;
import com.github.benmanes.caffeine.cache.impl.Ehcache2;
import com.github.benmanes.caffeine.cache.impl.Ehcache3;
import com.github.benmanes.caffeine.cache.impl.GuavaCache;
import com.github.benmanes.caffeine.cache.impl.LinkedHashMapCache;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

/**
 * A factory for creating a {@link BasicCache} implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum CacheType {

  /* ---------------- Unbounded -------------- */

  ConcurrentHashMapV7 { // see OpenJDK/7u40-b43
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(
          new ConcurrentHashMapV7<>(maximumSize, 0.75f, CONCURRENCY_LEVEL));
    }
  },
  ConcurrentHashMap {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(new ConcurrentHashMap<K, V>(maximumSize));
    }
  },
  NonBlockingHashMap {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      // Note that writes that update an entry to the same reference are short circuited
      // and do not mutate the hash table. This makes those writes equal to a read.
      return new ConcurrentMapCache<>(new NonBlockingHashMap<K, V>(maximumSize));
    }
  },

  /* ---------------- Bounded -------------- */

  Cache2k_ClockProPlus {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new Cache2k<>(ClockProPlusCache.class, maximumSize);
    }
  },
  Cache2k_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new Cache2k<>(LruCache.class, maximumSize);
    }
  },
  Caffeine {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new CaffeineCache<>(maximumSize);
    }
  },
  ConcurrentLinkedHashMap {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(
          new ConcurrentLinkedHashMap.Builder<K, V>()
            .maximumWeightedCapacity(maximumSize)
            .build());
    }
  },
  Ehcache2_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new Ehcache2<>(MemoryStoreEvictionPolicy.LRU, maximumSize);
    }
  },
  Ehcache3_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new Ehcache3<>(Prioritizer.LRU, maximumSize);
    }
  },
  Guava {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new GuavaCache<>(maximumSize);
    }
  },
  Infinispan_Old_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(new BoundedConcurrentHashMap<>(
          maximumSize, CONCURRENCY_LEVEL, BoundedConcurrentHashMap.Eviction.LRU,
          AnyEquivalence.getInstance(), AnyEquivalence.getInstance()));
    }
  },
  Infinispan_New_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(
          new BoundedEquivalentConcurrentHashMapV8<>(maximumSize, Eviction.LRU,
              BoundedEquivalentConcurrentHashMapV8.getNullEvictionListener(),
              AnyEquivalence.getInstance(), AnyEquivalence.getInstance()));
    }
  },
  Jackrabbit {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new GuavaCache<>(new CacheLIRS<>(maximumSize));
    }
  },
  LinkedHashMap_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new LinkedHashMapCache<K, V>(true, maximumSize);
    }
  };

  /** The number of hash table segments. */
  public static final int CONCURRENCY_LEVEL = 64;

  /**
   * Creates the cache with the maximum size. Note that some implementations may evict prior to
   * this threshold and it is the caller's responsibility to adjust accordingly.
   */
  public abstract <K, V> BasicCache<K, V> create(int maximumSize);
}
