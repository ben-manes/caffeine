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

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ForwardingCollection;
import com.google.common.collect.ForwardingConcurrentMap;
import com.google.common.collect.ForwardingSet;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * A Caffeine-backed cache through a Guava facade.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("serial")
class CaffeinatedGuavaCache<K, V> implements Cache<K, V>, Serializable {
  private static final long serialVersionUID = 1L;

  private final com.github.benmanes.caffeine.cache.Cache<K, V> cache;
  private transient @Nullable ConcurrentMap<K, V> mapView;

  CaffeinatedGuavaCache(com.github.benmanes.caffeine.cache.Cache<K, V> cache) {
    this.cache = requireNonNull(cache);
  }

  @Override @Nullable
  public V getIfPresent(Object key) {
    @SuppressWarnings("unchecked")
    K castedKey = (K) key;
    return cache.getIfPresent(castedKey);
  }

  @Override
  @SuppressWarnings({"NullAway", "PMD.ExceptionAsFlowControl", "PMD.PreserveStackTrace"})
  public V get(K key, Callable<? extends V> valueLoader) throws ExecutionException {
    requireNonNull(valueLoader);
    try {
      return cache.get(key, k -> {
        try {
          V value = valueLoader.call();
          if (value == null) {
            throw new InvalidCacheLoadException("null value");
          }
          return value;
        } catch (InvalidCacheLoadException e) {
          throw e;
        } catch (RuntimeException e) {
          throw new UncheckedExecutionException(e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new CacheLoaderException(e);
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
    @SuppressWarnings("unchecked")
    var castedKeys = (Iterable<? extends K>) keys;
    return ImmutableMap.copyOf(cache.getAllPresent(castedKeys));
  }

  @Override
  public void put(K key, V value) {
    cache.put(key, value);
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    cache.putAll(m);
  }

  @Override
  public void invalidate(Object key) {
    @SuppressWarnings("unchecked")
    K castedKey = (K) key;
    cache.invalidate(castedKey);
  }

  @Override
  public void invalidateAll(Iterable<?> keys) {
    @SuppressWarnings("unchecked")
    var castedKeys = (Iterable<? extends K>) keys;
    cache.invalidateAll(castedKeys);
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
    var stats = cache.stats();
    return new CacheStats(stats.hitCount(), stats.missCount(), stats.loadSuccessCount(),
        stats.loadFailureCount(), stats.totalLoadTime(), stats.evictionCount());
  }

  @Override
  public ConcurrentMap<K, V> asMap() {
    return (mapView == null) ? (mapView = new AsMapView()) : mapView;
  }

  @Override
  public void cleanUp() {
    cache.cleanUp();
  }

  final class AsMapView extends ForwardingConcurrentMap<K, V> {
    @Nullable Set<Entry<K, V>> entrySet;
    @Nullable Collection<V> values;
    @Nullable Set<K> keySet;

    @Override public boolean containsKey(@Nullable Object key) {
      return (key != null) && delegate().containsKey(key);
    }
    @Override public boolean containsValue(@Nullable Object value) {
      return (value != null) && delegate().containsValue(value);
    }
    @Override public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
      delegate().replaceAll(function);
    }
    @Override public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
      return delegate().computeIfAbsent(key, mappingFunction);
    }
    @Override public V computeIfPresent(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      return delegate().computeIfPresent(key, remappingFunction);
    }
    @Override public V compute(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      return delegate().compute(key, remappingFunction);
    }
    @Override public V merge(K key, V value,
        BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
      return delegate().merge(key, value, remappingFunction);
    }
    @Override public Set<K> keySet() {
      return (keySet == null) ? (keySet = new KeySetView()) : keySet;
    }
    @Override public Collection<V> values() {
      return (values == null) ? (values = new ValuesView()) : values;
    }
    @Override public Set<Entry<K, V>> entrySet() {
      return (entrySet == null) ? (entrySet = new EntrySetView()) : entrySet;
    }
    @Override protected ConcurrentMap<K, V> delegate() {
      return cache.asMap();
    }
  }

  final class KeySetView extends ForwardingSet<K> {
    @Override public boolean removeIf(Predicate<? super K> filter) {
      return delegate().removeIf(filter);
    }
    @SuppressWarnings("NullAway")
    @Override public boolean remove(Object o) {
      return (o != null) && delegate().remove(o);
    }
    @Override protected Set<K> delegate() {
      return cache.asMap().keySet();
    }
  }

  final class ValuesView extends ForwardingCollection<V> {
    @Override public boolean removeIf(Predicate<? super V> filter) {
      return delegate().removeIf(filter);
    }
    @Override public boolean remove(@Nullable Object o) {
      return (o != null) && delegate().remove(o);
    }
    @Override protected Collection<V> delegate() {
      return cache.asMap().values();
    }
  }

  final class EntrySetView extends ForwardingSet<Entry<K, V>> {
    @SuppressWarnings("NullAway")
    @Override public boolean add(Entry<K, V> entry) {
      throw new UnsupportedOperationException();
    }
    @Override public boolean addAll(Collection<? extends Entry<K, V>> entry) {
      throw new UnsupportedOperationException();
    }
    @Override public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
      return delegate().removeIf(filter);
    }
    @Override protected Set<Entry<K, V>> delegate() {
      return cache.asMap().entrySet();
    }
  }

  static final class CacheLoaderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CacheLoaderException(Exception e) {
      super(e);
    }

    @CanIgnoreReturnValue
    @SuppressWarnings({"NonSynchronizedMethodOverridesSynchronizedMethod",
        "UnsynchronizedOverridesSynchronized"})
    @Override public Throwable fillInStackTrace() {
      return this;
    }
  }
}
