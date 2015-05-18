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

import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.ehcache.config.Eviction.Prioritizer;
import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.commons.util.concurrent.jdk8backported.BoundedEquivalentConcurrentHashMapV8;
import org.infinispan.commons.util.concurrent.jdk8backported.BoundedEquivalentConcurrentHashMapV8.Eviction;
import org.infinispan.util.concurrent.BoundedConcurrentHashMap;

import com.github.benmanes.caffeine.cache.impl.ConcurrentHashMapV7;
import com.github.benmanes.caffeine.cache.impl.ConcurrentMapCache;
import com.github.benmanes.caffeine.cache.impl.Ehcache2;
import com.github.benmanes.caffeine.cache.impl.Ehcache3;
import com.github.benmanes.caffeine.cache.impl.LinkedHashMapCache;
import com.github.benmanes.caffeine.cache.tracing.Tracer;
import com.google.common.cache.CacheBuilder;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

import net.sf.ehcache.store.MemoryStoreEvictionPolicy;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum CacheType {
  Caffeine {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      System.setProperty(Tracer.TRACING_ENABLED, "false");
      return new ConcurrentMapCache<>(
          com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
              .maximumSize(maximumSize)
              .<K, V>build()
              .asMap());
    }
  },
  ConcurrentHashMapV7 { // unbounded, see OpenJDK/7u40-b43
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(
          new ConcurrentHashMapV7<>(maximumSize, 0.75f, CONCURRENCY_LEVEL));
    }
  },
  ConcurrentHashMap { // unbounded
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(new ConcurrentHashMap<K, V>(maximumSize));
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
  Guava {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(
          CacheBuilder.newBuilder()
              .concurrencyLevel(CONCURRENCY_LEVEL)
              .maximumSize(maximumSize)
              .<K, V>build()
              .asMap());
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
  LinkedHashMap_Lru {
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new LinkedHashMapCache<K, V>(true, maximumSize);
    }
  },
  NonBlockingHashMap { // unbounded
    @Override public <K, V> BasicCache<K, V> create(int maximumSize) {
      return new ConcurrentMapCache<>(
          new NonBlockingHashMap<K, V>(maximumSize));
    }
  };

  /** The number of hash table segments. */
  static final int CONCURRENCY_LEVEL = 64;

  /**
   * Creates the cache with the maximum size. Note that some implementations may evict prior to
   * this threshold and it is the caller's responsibility to adjust accordingly.
   */
  public abstract <K, V> BasicCache<K, V> create(int maximumSize);
}
