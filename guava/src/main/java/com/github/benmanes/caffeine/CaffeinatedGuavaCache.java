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
package com.github.benmanes.caffeine;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * A Caffeine-backed cache through a Guava facade.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
class CaffeinatedGuavaCache<K, V> implements Cache<K, V> {
  final com.github.benmanes.caffeine.cache.Cache<K, V> cache;

  CaffeinatedGuavaCache(com.github.benmanes.caffeine.cache.Cache<K, V> cache) {
    this.cache = requireNonNull(cache);
  }

  @Override @Nullable
  public V getIfPresent(Object key) {
    return cache.getIfPresent(key);
  }

  @Override
  public V get(K key, Callable<? extends V> valueLoader) throws ExecutionException {
    try {
      return cache.get(key, k -> {
        try {
          return valueLoader.call();
        } catch (RuntimeException e) {
          throw new UncheckedExecutionException(e);
        } catch (Exception e) {
          throw new CacheLoaderException(e);
        } catch (Error e) {
          throw new ExecutionError(e);
        }
      });
    } catch (CacheLoaderException e) {
      throw new ExecutionException(e.getCause());
    }
  }

  @Override
  public ImmutableMap<K, V> getAllPresent(Iterable<?> keys) {
    return ImmutableMap.copyOf(cache.getAllPresent(keys));
  }

  @Override
  public void put(K key, V value) {
    cache.put(key, value);
  }

  @Override
  public void putAll(Map<? extends K,? extends V> m) {
    cache.putAll(m);
  }

  @Override
  public void invalidate(Object key) {
    cache.invalidate(key);
  }

  @Override
  public void invalidateAll(Iterable<?> keys) {
    cache.invalidateAll(keys);
  }

  @Override
  public void invalidateAll() {
    cache.invalidateAll();
  }

  @Override
  public long size() {
    return cache.estimatedSize();
  }

  @Override
  public CacheStats stats() {
    com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
    return new CacheStats(stats.hitCount(), stats.missCount(), stats.loadSuccessCount(),
        stats.loadFailureCount(), stats.totalLoadTime(), stats.evictionCount());
  }

  @Override
  public ConcurrentMap<K, V> asMap() {
    return cache.asMap();
  }

  @Override
  public void cleanUp() {
    cache.cleanUp();
  }

  static final class CacheLoaderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CacheLoaderException(Exception e) {
      super(e);
    }
  }
}
