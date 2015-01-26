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

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.cache.Weigher;
import com.google.common.collect.ForwardingConcurrentMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class GuavaLocalCache {

  private GuavaLocalCache() {}

  /** Returns a Guava-backed cache. */
  public static <K, V> Cache<K, V> newGuavaCache(CacheContext context) {
    CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder();
    if (context.initialCapacity != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity.size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximumSize != MaximumSize.DISABLED) {
      if (context.weigher == CacheWeigher.DEFAULT) {
        builder.maximumSize(context.maximumSize.max());
      } else {
        builder.weigher(new GuavaWeigher<Object, Object>(context.weigher));
        builder.maximumWeight(context.maximumWeight());
      }
    }
    if (context.afterAccess != Expire.DISABLED) {
      builder.expireAfterAccess(context.afterAccess.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.afterWrite != Expire.DISABLED) {
      builder.expireAfterWrite(context.afterWrite.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.refresh != Expire.DISABLED) {
      builder.refreshAfterWrite(context.refresh.timeNanos(), TimeUnit.NANOSECONDS);
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
      builder.removalListener(new GuavaRemovalListener<>(context.removalListener()));
    }
    Ticker ticker = (context.ticker == null) ? Ticker.systemTicker() : context.ticker;
    if (context.loader == null) {
      context.cache = new GuavaCache<>(builder.<Integer, Integer>build(), ticker);
    } else if (context.loader().isBulk()) {
      context.cache = new GuavaLoadingCache<>(builder.build(
          new BulkLoader<Integer, Integer>(context.loader())), ticker);
    } else {
      context.cache = new GuavaLoadingCache<>(builder.build(
          new SingleLoader<Integer, Integer>(context.loader())), ticker);
    }
    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) context.cache;
    return castedCache;
  }

  static class GuavaCache<K, V> implements Cache<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    private final com.google.common.cache.Cache<K, V> cache;
    private final Ticker ticker;

    transient StatsCounter statsCounter;

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
        return cache.get(key, () -> {
          V value = mappingFunction.apply(key);
          if (value == null) {
            throw new CacheMissException();
          }
          return value;
        });
      } catch (UncheckedExecutionException e) {
        if (e.getCause() instanceof CacheMissException) {
          return null;
        }
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
    public long estimatedSize() {
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
          } catch (RuntimeException | Error e) {
            statsCounter.recordLoadException((ticker.read() - now));
            throw e;
          }
        }
        @Override
        public V computeIfPresent(K key,
            BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
          requireNonNull(remappingFunction);
          V oldValue;
          long now = ticker.read();
          if ((oldValue = get(key)) != null) {
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
            } catch (RuntimeException | Error e) {
              statsCounter.recordLoadException(ticker.read() - now);
              throw e;
            }
          } else {
            return null;
          }
        }
        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
          requireNonNull(remappingFunction);
          V oldValue = get(key);

          long now = ticker.read();
          try {
            V newValue = remappingFunction.apply(key, oldValue);
            if (newValue == null) {
              if (oldValue != null || containsKey(key)) {
                remove(key);
                statsCounter.recordLoadException(ticker.read() - now);
                return null;
              } else {
                return null;
              }
            } else {
              statsCounter.recordLoadSuccess(ticker.read() - now);
              put(key, newValue);
              return newValue;
            }
          } catch (RuntimeException | Error e) {
            statsCounter.recordLoadException(ticker.read() - now);
            throw e;
          }
        }
        @Override
        public V merge(K key, V value,
            BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
          requireNonNull(remappingFunction);
          requireNonNull(value);
          V oldValue = get(key);
          for (;;) {
            if (oldValue != null) {
              long now = ticker.read();
              try {
                V newValue = remappingFunction.apply(oldValue, value);
                if (newValue != null) {
                  if (replace(key, oldValue, newValue)) {
                    statsCounter.recordLoadSuccess(ticker.read() - now);
                    return newValue;
                  }
                } else if (remove(key, oldValue)) {
                  statsCounter.recordLoadException(ticker.read() - now);
                  return null;
                }
              } catch (RuntimeException | Error e) {
                statsCounter.recordLoadException(ticker.read() - now);
                throw e;
              }
              oldValue = get(key);
            } else {
              if ((oldValue = putIfAbsent(key, value)) == null) {
                return value;
              }
            }
          }
        }
        @Override
        protected ConcurrentMap<K, V> delegate() {
          return cache.asMap();
        }

        private void readObject(ObjectInputStream stream) throws InvalidObjectException {
          statsCounter = new SimpleStatsCounter();
        }
      };
    }

    @Override
    public void cleanUp() {
      cache.cleanUp();
    }

    @Override
    public Policy<K, V> policy() {
      return new Policy<K, V>() {
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

  static class GuavaLoadingCache<K, V> extends GuavaCache<K, V>
      implements LoadingCache<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

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
        if (e.getCause() instanceof CacheMissException) {
          return null;
        }
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
        if (e.getCause() instanceof CacheMissException) {
          return ImmutableMap.of();
        }
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

  static final class GuavaWeigher<K, V> implements Weigher<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    final com.github.benmanes.caffeine.cache.Weigher<K, V> weigher;

    GuavaWeigher(com.github.benmanes.caffeine.cache.Weigher<K, V> weigher) {
      this.weigher = weigher;
    }

    @Override public int weigh(K key, V value) {
      return weigher.weigh(key, value);
    }
  }

  static final class GuavaRemovalListener<K, V> implements RemovalListener<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    final com.github.benmanes.caffeine.cache.RemovalListener<K, V> delegate;

    GuavaRemovalListener(com.github.benmanes.caffeine.cache.RemovalListener<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onRemoval(RemovalNotification<K, V> notification) {
      RemovalCause cause = RemovalCause.valueOf(notification.getCause().name());
      delegate.onRemoval(new com.github.benmanes.caffeine.cache.RemovalNotification<K, V>(
          notification.getKey(), notification.getValue(), cause));
    }
  }

  static class SingleLoader<K, V> extends CacheLoader<K, V> implements Serializable {
    private static final long serialVersionUID = 1L;

    final com.github.benmanes.caffeine.cache.CacheLoader<K, V> delegate;

    SingleLoader(com.github.benmanes.caffeine.cache.CacheLoader<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public V load(K key) throws Exception {
      V value = delegate.load(key);
      if (value == null) {
        throw new CacheMissException();
      }
      return value;
    }
  }

  static class BulkLoader<K, V> extends SingleLoader<K, V> {
    private static final long serialVersionUID = 1L;

    BulkLoader(com.github.benmanes.caffeine.cache.CacheLoader<K, V> delegate) {
      super(delegate);
    }

    @Override
    public Map<K, V> loadAll(Iterable<? extends K> keys) throws Exception {
      return delegate.loadAll(keys);
    }
  }

  static final class CacheMissException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
