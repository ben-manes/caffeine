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
package com.github.benmanes.caffeine.guava;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ExecutionException;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * A Caffeine-backed loading cache through a Guava facade.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeinatedGuavaLoadingCache<K, V> extends CaffeinatedGuavaCache<K, V>
    implements LoadingCache<K, V> {
  final com.github.benmanes.caffeine.cache.LoadingCache<K, V> cache;

  CaffeinatedGuavaLoadingCache(com.github.benmanes.caffeine.cache.LoadingCache<K, V> cache) {
    super(cache);
    this.cache = cache;
  }

  @Override
  public V get(K key) throws ExecutionException {
    return cache.get(key);
  }

  @Override
  public V getUnchecked(K key) {
    try {
      return cache.get(key);
    } catch (NullPointerException | InvalidCacheLoadException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new UncheckedExecutionException(e);
    } catch (Exception e) {
      throw new CacheLoaderException(e);
    } catch (Error e) {
      throw new ExecutionError(e);
    }
  }

  @Override
  public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
    return ImmutableMap.copyOf(cache.getAll(keys));
  }

  @Override
  public V apply(K key) {
    return cache.get(key);
  }

  @Override
  public void refresh(K key) {
    cache.refresh(key);
  }

  // TODO(ben): Delegate to loadAll, reload, etc.
  static final class CaffeinatedGuavaCacheLoader<K, V> implements CacheLoader<K, V> {
    final com.google.common.cache.CacheLoader<K, V> cacheLoader;

    CaffeinatedGuavaCacheLoader(com.google.common.cache.CacheLoader<K, V> cacheLoader) {
      this.cacheLoader = requireNonNull(cacheLoader);
    }

    @Override
    public V load(K key) {
      try {
        V value = cacheLoader.load(key);
        if (value == null) {
          throw new InvalidCacheLoadException("null value");
        }
        return value;
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Exception e) {
        throw new CacheLoaderException(e);
      }
    }
  }
}
