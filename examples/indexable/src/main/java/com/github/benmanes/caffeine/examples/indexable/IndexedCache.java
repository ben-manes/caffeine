/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.examples.indexable;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Striped;

/**
 * A cache abstraction that allows the entry to looked up by alternative keys. This approach mirrors
 * a database table where a row is stored by its primary key, it contains all of the columns that
 * identify it, and the unique indexes are additional mappings defined by the column mappings. This
 * class similarly stores the in the value once in the cache by its primary key and maintains a
 * secondary mapping for lookups by using indexing functions to derive the keys.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IndexedCache<K, V> {
  final ConcurrentMap<K, Index<K>> indexes;
  final Function<K, V> mappingFunction;
  final Function<V, Index<K>> indexer;
  final Striped<Lock> locks;
  final Cache<K, V> store;

  private IndexedCache(Caffeine<Object, Object> cacheBuilder, Function<K, V> mappingFunction,
      Function<V, K> primary, Set<Function<V, K>> secondaries) {
    this.locks = Striped.lock(1_000);
    this.mappingFunction = mappingFunction;
    this.indexes = new ConcurrentHashMap<>();
    this.store = cacheBuilder
        .evictionListener((key, value, cause) ->
            indexes.keySet().removeAll(indexes.get(key).allKeys()))
        .build();
    this.indexer = value -> new Index<>(primary.apply(value),
        secondaries.stream().map(indexer -> indexer.apply(value)).collect(toImmutableSet()));
  }

  /** Returns the value associated with the key or {@code null} if not found. */
  public V getIfPresent(K key) {
    var index = indexes.get(key);
    return (index == null) ? null : store.getIfPresent(index.primaryKey());
  }

  /**
   * Returns the value associated with the key, obtaining that value from the
   * {@code mappingFunction} if necessary. The entire method invocation is performed atomically, so
   * the function is applied at most once per key. As the value may be looked up by alternative
   * keys, those function invocations may be executed in parallel and will replace any existing
   * mappings when completed.
   */
  public V get(K key) {
    var value = getIfPresent(key);
    if (value != null) {
      return value;
    }

    var lock = locks.get(key);
    lock.lock();
    try {
      value = getIfPresent(key);
      if (value != null) {
        return value;
      }

      value = mappingFunction.apply(key);
      if (value == null) {
        return null;
      }

      put(value);
      return value;
    } finally {
      lock.unlock();
    }
  }

  /** Associates the {@code value} with its keys, replacing the old value and keys if present. */
  public V put(V value) {
    requireNonNull(value);
    var index = indexer.apply(value);
    return store.asMap().compute(index.primaryKey(), (key, oldValue) -> {
      if (oldValue != null) {
        indexes.keySet().removeAll(Sets.difference(
            indexes.get(index.primaryKey()).allKeys(), index.allKeys()));
      }
      for (var indexKey : index.allKeys()) {
        indexes.put(indexKey, index);
      }
      return value;
    });
  }

  /** Discards any cached value and its keys. */
  public void invalidate(K key) {
    var index = indexes.get(key);
    if (index == null) {
      return;
    }

    store.asMap().computeIfPresent(index.primaryKey(), (k, v) -> {
      indexes.keySet().removeAll(index.allKeys());
      return null;
    });
  }

  private record Index<K>(K primaryKey, Set<K> secondaryKeys) {
    public Set<K> allKeys() {
      return Sets.union(Set.of(primaryKey), secondaryKeys);
    }
  }

  /** This builder could be extended to support most cache options, e.g. excluding for weak keys. */
  public static final class Builder<K, V> {
    final Caffeine<Object, Object> cacheBuilder;
    final ImmutableSet.Builder<Function<V, K>> secondaries;

    Function<V, K> primary;

    public Builder() {
      cacheBuilder = Caffeine.newBuilder();
      secondaries = ImmutableSet.builder();
    }

    /** See {@link Caffeine#expireAfterWrite(Duration)}. */
    public Builder<K, V> expireAfterWrite(Duration duration) {
      cacheBuilder.expireAfterWrite(duration);
      return this;
    }

    /** See {@link Caffeine#ticker(Duration)}. */
    public Builder<K, V> ticker(Ticker ticker) {
      cacheBuilder.ticker(ticker);
      return this;
    }

    /** Adds the functions to extract the indexable keys. */
    public Builder<K, V> primaryKey(Function<V, K> primary) {
      this.primary = requireNonNull(primary);
      return this;
    }

    /** Adds the functions to extract the indexable keys. */
    public Builder<K, V> addSecondaryKey(Function<V, K> secondary) {
      secondaries.add(requireNonNull(secondary));
      return this;
    }

    public IndexedCache<K, V> build(Function<K, V> mappingFunction) {
      requireNonNull(primary);
      requireNonNull(mappingFunction);
      return new IndexedCache<K, V>(cacheBuilder, mappingFunction, primary, secondaries.build());
    }
  }
}
