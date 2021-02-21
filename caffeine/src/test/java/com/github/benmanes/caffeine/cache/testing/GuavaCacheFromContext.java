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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Policy;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.google.common.base.Ticker;
import com.google.common.cache.AbstractCache.SimpleStatsCounter;
import com.google.common.cache.AbstractCache.StatsCounter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.cache.Weigher;
import com.google.common.collect.ForwardingConcurrentMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PreferJavaTimeOverload")
public final class GuavaCacheFromContext {
  private static final ThreadLocal<Exception> error = new ThreadLocal<>();

  private GuavaCacheFromContext() {}

  /** Returns a Guava-backed cache. */
  @SuppressWarnings("CheckReturnValue")
  public static <K, V> Cache<K, V> newGuavaCache(CacheContext context) {
    checkState(!context.isAsync(), "Guava caches are synchronous only");

    CacheBuilder<Object, Object> builder = CacheBuilder.newBuilder();
    context.guava = builder;

    builder.concurrencyLevel(1);
    if (context.initialCapacity != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity.size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximumSize != Maximum.DISABLED) {
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
    if (context.expires() || context.refreshes()) {
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
      boolean translateZeroExpire = (context.afterAccess == Expire.IMMEDIATELY) ||
          (context.afterWrite == Expire.IMMEDIATELY);
      builder.removalListener(new GuavaRemovalListener<>(
          translateZeroExpire, context.removalListener));
    }
    Ticker ticker = (context.ticker == null) ? Ticker.systemTicker() : context.ticker();
    if (context.loader == null) {
      context.cache = new GuavaCache<>(builder.<Integer, Integer>build(),
          ticker, context.isRecordingStats());
    } else if (context.loader().isBulk()) {
      context.cache = new GuavaLoadingCache<>(builder.build(
          new BulkLoader<Integer, Integer>(context.loader())),
          ticker, context.isRecordingStats());
    } else {
      context.cache = new GuavaLoadingCache<>(builder.build(
          new SingleLoader<Integer, Integer>(context.loader())),
          ticker, context.isRecordingStats());
    }
    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) context.cache;
    return castedCache;
  }

  static class GuavaCache<K, V> implements Cache<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    private final com.google.common.cache.Cache<K, V> cache;
    private final boolean isRecordingStats;
    private final Ticker ticker;

    transient StatsCounter statsCounter;

    GuavaCache(com.google.common.cache.Cache<K, V> cache, Ticker ticker, boolean isRecordingStats) {
      this.statsCounter = new SimpleStatsCounter();
      this.isRecordingStats = isRecordingStats;
      this.cache = requireNonNull(cache);
      this.ticker = ticker;
    }

    @Override
    public V getIfPresent(Object key) {
      return cache.getIfPresent(key);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
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
    public Map<K, V> getAllPresent(Iterable<? extends K> keys) {
      for (K key : keys) {
        requireNonNull(key);
      }
      return cache.getAllPresent(keys);
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys,
        Function<? super Set<? extends K>, ? extends Map<? extends K, ? extends V>> mappingFunction) {
      keys.forEach(Objects::requireNonNull);
      requireNonNull(mappingFunction);

      Map<K, V> found = getAllPresent(keys);
      Set<K> keysToLoad = Sets.difference(ImmutableSet.copyOf(keys), found.keySet());
      if (keysToLoad.isEmpty()) {
        return found;
      }

      long start = ticker.read();
      try {
        var loaded = mappingFunction.apply(keysToLoad);
        loaded.forEach(cache::put);
        long end = ticker.read();
        statsCounter.recordLoadSuccess(end - start);

        Map<K, V> result = new LinkedHashMap<>();
        for (K key : keys) {
          V value = found.get(key);
          if (value == null) {
            value = loaded.get(key);
          }
          if (value != null) {
            result.put(key, value);
          }
        }
        return Collections.unmodifiableMap(result);
      } catch (Throwable t) {
        long end = ticker.read();
        statsCounter.recordLoadException(end - start);
        throw t;
      }
    }

    @Override
    public void put(K key, V value) {
      requireNonNull(key);
      requireNonNull(value);
      cache.put(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      cache.putAll(map);
    }

    @Override
    public void invalidate(K key) {
      cache.invalidate(key);
    }

    @Override
    public void invalidateAll(Iterable<? extends K> keys) {
      keys.forEach(this::invalidate);
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
      return CacheStats.of(stats.hitCount(), stats.missCount(), stats.loadSuccessCount(),
          stats.loadExceptionCount(), stats.totalLoadTime(), stats.evictionCount(), 0L);
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
        public boolean replace(K key, V oldValue, V newValue) {
          requireNonNull(oldValue);
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
              V v = delegate().putIfAbsent(key, value);
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
                statsCounter.recordLoadException(ticker.read() - now);
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

        @SuppressWarnings({"UnusedVariable", "UnusedMethod"})
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
        @Override public boolean isRecordingStats() {
          return isRecordingStats;
        }
        @Override public V getIfPresentQuietly(K key) {
          return cache.asMap().get(key);
        }
        @Override public Map<K, CompletableFuture<V>> refreshes() {
          return Map.of();
        }
        @Override public Optional<Eviction<K, V>> eviction() {
          return Optional.empty();
        }
        @Override public Optional<FixedExpiration<K, V>> expireAfterAccess() {
          return Optional.empty();
        }
        @Override public Optional<FixedExpiration<K, V>> expireAfterWrite() {
          return Optional.empty();
        }
        @Override public Optional<VarExpiration<K, V>> expireVariably() {
          return Optional.empty();
        }
        @Override public Optional<FixedRefresh<K, V>> refreshAfterWrite() {
          return Optional.empty();
        }
      };
    }
  }

  static class GuavaLoadingCache<K, V> extends GuavaCache<K, V>
      implements LoadingCache<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    private final com.google.common.cache.LoadingCache<K, V> cache;

    GuavaLoadingCache(com.google.common.cache.LoadingCache<K, V> cache,
        Ticker ticker, boolean isRecordingStats) {
      super(cache, ticker, isRecordingStats);
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
    public CompletableFuture<V> refresh(K key) {
      error.set(null);
      cache.refresh(key);

      var e = error.get();
      if (e == null) {
        return CompletableFuture.completedFuture(cache.asMap().get(key));
      } else if (e instanceof CacheMissException) {
        return CompletableFuture.completedFuture(null);
      }

      error.remove();
      return CompletableFuture.failedFuture(e);
    }

    @Override
    public CompletableFuture<Map<K, V>> refreshAll(Iterable<? extends K> keys) {
      Map<K, CompletableFuture<V>> result = new LinkedHashMap<>();
      for (K key : keys) {
        result.computeIfAbsent(key, this::refresh);
      }
      return composeResult(result);
    }

    CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<V>> futures) {
      if (futures.isEmpty()) {
        return CompletableFuture.completedFuture(Collections.emptyMap());
      }
      @SuppressWarnings("rawtypes")
      CompletableFuture<?>[] array = futures.values().toArray(new CompletableFuture[0]);
      return CompletableFuture.allOf(array).thenApply(ignored -> {
        Map<K, V> result = new LinkedHashMap<>(futures.size());
        futures.forEach((key, future) -> {
          V value = future.getNow(null);
          if (value != null) {
            result.put(key, value);
          }
        });
        return Collections.unmodifiableMap(result);
      });
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
    final boolean translateZeroExpire;

    GuavaRemovalListener(boolean translateZeroExpire,
        com.github.benmanes.caffeine.cache.RemovalListener<K, V> delegate) {
      this.translateZeroExpire = translateZeroExpire;
      this.delegate = delegate;
    }

    @Override
    public void onRemoval(RemovalNotification<K, V> notification) {
      RemovalCause cause = RemovalCause.valueOf(notification.getCause().name());
      if (translateZeroExpire && (cause == RemovalCause.SIZE)) {
        // Guava internally uses sizing logic for null cache case
        cause = RemovalCause.EXPIRED;
      }
      delegate.onRemoval(notification.getKey(), notification.getValue(), cause);
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
      try {
        error.set(null);
        V value = delegate.load(key);
        if (value == null) {
          throw new CacheMissException();
        }
        return value;
      } catch (Exception e) {
        error.set(e);
        throw e;
      }
    }
  }

  static class BulkLoader<K, V> extends SingleLoader<K, V> {
    private static final long serialVersionUID = 1L;

    BulkLoader(com.github.benmanes.caffeine.cache.CacheLoader<K, V> delegate) {
      super(delegate);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<K, V> loadAll(Iterable<? extends K> keys) throws Exception {
      var keysToLoad = (keys instanceof Set) ? (Set<? extends K>) keys : ImmutableSet.copyOf(keys);
      @SuppressWarnings("unchecked")
      var loaded = (Map<K, V>) delegate.loadAll(keysToLoad);
      return loaded;
    }
  }

  static final class CacheMissException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
