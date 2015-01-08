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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.Futures;
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
    requireNonNull(key);
    try {
      return cache.get(key);
    } catch (InvalidCacheLoadException e) {
      throw e;
    } catch (CacheLoaderException e) {
      throw new ExecutionException(e.getCause());
    } catch (RuntimeException e) {
      throw new UncheckedExecutionException(e);
    } catch (Error e) {
      throw new ExecutionError(e);
    }
  }

  @Override
  public V getUnchecked(K key) {
    try {
      return cache.get(key);
    } catch (NullPointerException | InvalidCacheLoadException e) {
      throw e;
    } catch (CacheLoaderException e) {
      throw new UncheckedExecutionException(e.getCause());
    } catch (Exception e) {
      throw new UncheckedExecutionException(e);
    } catch (Error e) {
      throw new ExecutionError(e);
    }
  }

  @Override
  public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
    try {
      return ImmutableMap.copyOf(cache.getAll(keys));
    } catch (NullPointerException | InvalidCacheLoadException e) {
      throw e;
    } catch (CacheLoaderException e) {
      throw new ExecutionException(e.getCause());
    } catch (Exception e) {
      throw new UncheckedExecutionException(e);
    } catch (Error e) {
      throw new ExecutionError(e);
    }
  }

  @Override
  public V apply(K key) {
    return cache.get(key);
  }

  @Override
  public void refresh(K key) {
    cache.refresh(key);
  }

  static class SingleLoader<K, V> implements CacheLoader<K, V> {
    final com.google.common.cache.CacheLoader<K, V> cacheLoader;

    SingleLoader(com.google.common.cache.CacheLoader<K, V> cacheLoader) {
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

    @Override
    public V reload(K key, V oldValue) {
      try {
        return Futures.getUnchecked(cacheLoader.reload(key, oldValue));
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  static final class BulkLoader<K, V> extends SingleLoader<K, V> {

    BulkLoader(com.google.common.cache.CacheLoader<K, V> cacheLoader) {
      super(cacheLoader);
    }

    @Override
    public Map<K, V> loadAll(Iterable<? extends K> keys) {
      try {
        Map<K, V> result = new HashMap<>();
        for (K key : keys) {
          V value = load(key);
          if (value == null) {
            throw new InvalidCacheLoadException("null value");
          }
          result.put(key, value);
        }
        return result;
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (Exception e) {
        throw new CacheLoaderException(e);
      }
    }
  }
}
