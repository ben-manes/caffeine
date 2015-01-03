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
package com.github.benmanes.caffeine.cache.testing;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import com.github.benmanes.caffeine.cache.Advanced;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalNotification;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.google.common.base.Throwables;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ForwardingConcurrentMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class GuavaLocalCache {

  private GuavaLocalCache() {}

  /** Returns a Guava-backed cache. */
  public static Cache<Integer, Integer> newGuavaCache(CacheContext context) {
    CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder();
    if (context.initialCapacity != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity.size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximumSize != MaximumSize.DISABLED) {
      builder.maximumSize(context.maximumSize.max());
    }
    if (context.afterAccess != Expire.DISABLED) {
      builder.expireAfterAccess(context.afterAccess.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.afterWrite != Expire.DISABLED) {
      builder.expireAfterWrite(context.afterWrite.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.expires()) {
      builder.ticker(context.ticker());
    }
    if (context.keyStrength == ReferenceType.WEAK) {
      builder.weakKeys();
    } else if (context.keyStrength == ReferenceType.SOFT) {
      throw new IllegalStateException();
    }
    if (context.valueStrength == ReferenceType.WEAK) {
      builder.weakValues();
    } else if (context.valueStrength == ReferenceType.SOFT) {
      builder.softValues();
    }
    if (context.removalListenerType != Listener.DEFAULT) {
      builder.<Integer, Integer>removalListener(notification -> {
        RemovalCause cause = RemovalCause.valueOf(notification.getCause().name());
        RemovalNotification<Integer, Integer> notif = new RemovalNotification<>(
            notification.getKey(), notification.getValue(), cause);
        context.removalListener().onRemoval(notif);
      });
    }
    Ticker ticker = (context.ticker == null) ? Ticker.systemTicker() : context.ticker;
    if (context.loader == null) {
      context.cache = new GuavaCache<>(builder.<Integer, Integer>build(), ticker);
    } else if (context.loader().isBulk()) {
      context.cache = new GuavaLoadingCache<>(builder.build(new CacheLoader<Integer, Integer>() {
        @Override
        public Integer load(Integer key) throws Exception {
          return context.loader().load(key);
        }
        @Override
        public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) throws Exception {
          return context.loader().loadAll(keys);
        }
      }), ticker);
    } else {
      context.cache = new GuavaLoadingCache<>(builder.build(new CacheLoader<Integer, Integer>() {
        @Override  public Integer load(Integer key) throws Exception {
          return context.loader().load(key);
        }
      }), ticker);
    }
    return context.cache;
  }

  static class GuavaCache<K, V> implements Cache<K, V> {
    private final com.google.common.cache.Cache<K, V> cache;
    private final StatsCounter statsCounter;
    private final Ticker ticker;

    GuavaCache(com.google.common.cache.Cache<K, V> cache, Ticker ticker) {
      this.statsCounter = new SimpleStatsCounter();
      this.cache = requireNonNull(cache);
      this.ticker = ticker;
    }

    @Override
    public V getIfPresent(Object key) {
      return cache.getIfPresent(key);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      try {
        return cache.get(key, () -> mappingFunction.apply(key));
      } catch (UncheckedExecutionException e) {
        throw (RuntimeException) e.getCause();
      } catch (ExecutionException e) {
        throw new CompletionException(e);
      } catch (ExecutionError e) {
        throw (Error) e.getCause();
      }
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
      requireNonNull(keys);
      Iterable<?> iterable = () -> StreamSupport.stream(keys.spliterator(), false)
            .map(key -> requireNonNull((Object) key)).iterator();
      return cache.getAllPresent(iterable);
    }

    @Override
    public void put(K key, V value) {
      cache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      cache.putAll(map);
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
      return cache.size();
    }

    @Override
    public CacheStats stats() {
      com.google.common.cache.CacheStats stats = statsCounter.snapshot().plus(cache.stats());
      return new CacheStats(stats.hitCount(), stats.missCount(), stats.loadSuccessCount(),
          stats.loadExceptionCount(), stats.totalLoadTime(), stats.evictionCount());
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      return new ForwardingConcurrentMap<K, V>() {
        @Override
        public boolean containsKey(Object key) {
          requireNonNull(key);
          return delegate().containsKey(key);
        }
        @Override
        public boolean containsValue(Object value) {
          requireNonNull(value);
          return delegate().containsValue(value);
        }
        @Override
        public V get(Object key) {
          requireNonNull(key);
          return delegate().get(key);
        }
        @Override
        public V remove(Object key) {
          requireNonNull(key);
          return delegate().remove(key);
        }
        @Override
        public boolean remove(Object key, Object value) {
          requireNonNull(key);
          return delegate().remove(key, value);
        }
        @Override
        public V replace(K key, V value) {
          requireNonNull(key);
          requireNonNull(value);
          return delegate().replace(key, value);
        }
        @Override
        public boolean replace(K key, V oldValue, V newValue) {
          requireNonNull(key);
          requireNonNull(oldValue);
          requireNonNull(newValue);
          return delegate().replace(key, oldValue, newValue);
        }
        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
          requireNonNull(mappingFunction);
          V value = getIfPresent(key);
          if (value != null) {
            return value;
          }
          long now = ticker.read();
          try {
            value = mappingFunction.apply(key);
            long loadTime = (ticker.read() - now);
            if (value == null) {
              statsCounter.recordLoadException(loadTime);
              return null;
            } else {
              statsCounter.recordLoadSuccess(loadTime);
              V v = putIfAbsent(key, value);
              return (v == null) ? value : v;
            }
          } catch (Throwable t) {
            statsCounter.recordLoadException((ticker.read() - now));
            throw Throwables.propagate(t);
          }
        }
        @Override
        public V computeIfPresent(K key,
            BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
          requireNonNull(remappingFunction);
          V oldValue;
          long now = ticker.read();
          if ((oldValue = get(key)) != null) {
            statsCounter.recordHits(1);
            try {
              V newValue = remappingFunction.apply(key, oldValue);
              long loadTime = ticker.read() - now;
              if (newValue == null) {
                statsCounter.recordLoadException(loadTime);
                remove(key);
                return null;
              } else {
                statsCounter.recordLoadSuccess(loadTime);
                put(key, newValue);
                return newValue;
              }
            } catch (Throwable t) {
              statsCounter.recordLoadException(ticker.read() - now);
              throw Throwables.propagate(t);
            }
          } else {
            return null;
          }
        }
        @Override
        protected ConcurrentMap<K, V> delegate() {
          return cache.asMap();
        }
      };
    }

    @Override
    public void cleanUp() {
      cache.cleanUp();
    }

    @Override
    public Advanced<K, V> advanced() {
      return new Advanced<K, V>() {
        @Override public Optional<Eviction<K, V>> eviction() {
          return Optional.empty();
        }
        @Override public Optional<Expiration<K, V>> expireAfterAccess() {
          return Optional.empty();
        }
        @Override public Optional<Expiration<K, V>> expireAfterWrite() {
          return Optional.empty();
        }
      };
    }
  }

  static class GuavaLoadingCache<K, V> extends GuavaCache<K, V> implements LoadingCache<K, V> {
    private final com.google.common.cache.LoadingCache<K, V> cache;

    GuavaLoadingCache(com.google.common.cache.LoadingCache<K, V> cache, Ticker ticker) {
      super(cache, ticker);
      this.cache = requireNonNull(cache);
    }

    @Override
    public V get(K key) {
      try {
        return cache.get(key);
      } catch (UncheckedExecutionException e) {
        throw (RuntimeException) e.getCause();
      } catch (ExecutionException e) {
        throw new CompletionException(e);
      } catch (ExecutionError e) {
        throw (Error) e.getCause();
      }
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys) {
      try {
        return cache.getAll(keys);
      } catch (UncheckedExecutionException e) {
        throw (RuntimeException) e.getCause();
      } catch (ExecutionException e) {
        throw new CompletionException(e);
      } catch (ExecutionError e) {
        throw (Error) e.getCause();
      }
    }

    @Override
    public void refresh(K key) {
      cache.refresh(key);
    }
  }
}
