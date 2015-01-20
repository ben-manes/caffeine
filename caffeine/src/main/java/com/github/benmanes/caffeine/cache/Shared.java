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
package com.github.benmanes.caffeine.cache;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalLoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;

/**
 * Shared code between cache implementations. This code will be moved into proper abstractions
 * as development matures.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class Shared {

  private Shared() {}

  /** Returns if the future has successfully completed. */
  static boolean isReady(@Nullable CompletableFuture<?> future) {
    return (future != null) && future.isDone() && !future.isCompletedExceptionally();
  }

  /** Returns the current value or null if either not done or failed. */
  static <V> V getIfReady(@Nullable CompletableFuture<V> future) {
    return isReady(future) ? future.join() : null;
  }

  /** Returns the value when done successfully or null if failed. */
  static <V> V getWhenSuccessful(@Nullable CompletableFuture<V> future) {
    try {
      return (future == null) ? null : future.get();
    } catch (InterruptedException | ExecutionException e) {
      return null;
    }
  }

  interface LocalCache<K, V> extends ConcurrentMap<K, V> {

    long mappingCount();

    V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction,
        boolean recordMiss, boolean isAsync);
    V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction,
        boolean isAsync);

    void cleanUp();

    @Nullable V getIfPresent(Object key, boolean recordStats);

    Map<K, V> getAllPresent(Iterable<?> keys);

    default void invalidateAll(Iterable<?> keys) {
      for (Object key : keys) {
        remove(key);
      }
    }

    RemovalListener<K, V> removalListener();
    StatsCounter statsCounter();
    boolean isRecordingStats();
    Ticker ticker();

    Executor executor();
  }

  /* ---------------- Async Loading Cache -------------- */

  static abstract class AsyncLocalLoadingCache<C extends LocalCache<K, CompletableFuture<V>>, K, V>
      implements AsyncLoadingCache<K, V> {
    static final Logger logger = Logger.getLogger(UnboundedLocalLoadingCache.class.getName());

    final C cache;
    final CacheLoader<K, V> loader;
    final boolean canBulkLoad;

    LoadingCacheView localCacheView;

    @SuppressWarnings("unchecked")
    AsyncLocalLoadingCache(C cache, CacheLoader<? super K, V> loader) {
      this.loader = (CacheLoader<K, V>) loader;
      this.canBulkLoad = canBulkLoad(loader);
      this.cache = cache;
    }

    protected abstract Policy<K, V> policy();

    static boolean canBulkLoad(CacheLoader<?, ?> loader) {
      try {
        Method loadAll = loader.getClass().getMethod(
            "loadAll", Iterable.class);
        Method asyncLoadAll = loader.getClass().getMethod(
            "asyncLoadAll", Iterable.class, Executor.class);
        return !loadAll.isDefault() || !asyncLoadAll.isDefault();
      } catch (NoSuchMethodException | SecurityException e) {
        logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
        return false;
      }
    }

    @Override
    public CompletableFuture<V> get(@Nonnull K key,
        @Nonnull Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
      return get(key, (k1, executor) -> CompletableFuture.<V>supplyAsync(
          () -> mappingFunction.apply(key), executor));
    }

    @Override
    public CompletableFuture<V> get(K key,
        BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction) {
      long now = cache.ticker().read();
      @SuppressWarnings("unchecked")
      CompletableFuture<V>[] result = new CompletableFuture[1];
      CompletableFuture<V> future = cache.computeIfAbsent(key, k -> {
        result[0] = mappingFunction.apply(key, cache.executor());
        requireNonNull(result[0]);
        return result[0];
      }, true);
      if (result[0] != null) {
        result[0].whenComplete((value, error) -> {
          long loadTime = cache.ticker().read() - now;
          if (value == null) {
            cache.statsCounter().recordLoadFailure(loadTime);
            cache.remove(key, result[0]);
          } else {
            // update the weight and expiration timestamps
            cache.replace(key, result[0], result[0]);
            cache.statsCounter().recordLoadSuccess(loadTime);
          }
        });
      }
      return future;
    }

    @Override
    public CompletableFuture<V> get(K key) {
      return get(key, (k, executor) -> loader.asyncLoad(key, executor));
    }

    @Override
    public CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys) {
      if (canBulkLoad) {
        return getAllBulk(keys);
      }

      Map<K, CompletableFuture<V>> result = new HashMap<>();
      for (K key : keys) {
        result.put(key, get(key));
      }
      return composeResult(result);
    }

    /** Computes all of the missing entries in a single {@link CacheLoader#asyncLoadAll} call. */
    CompletableFuture<Map<K, V>> getAllBulk(Iterable<? extends K> keys) {
      Map<K, CompletableFuture<V>> futures = new HashMap<>();
      Map<K, CompletableFuture<V>> proxies = new HashMap<>();
      for (K key : keys) {
        CompletableFuture<V> future = cache.getIfPresent(key, false);
        if (future == null) {
          CompletableFuture<V> proxy = new CompletableFuture<>();
          future = cache.putIfAbsent(key, proxy);
          if (future == null) {
            future = proxy;
            proxies.put(key, proxy);
          }
        }
        futures.put(key, future);
      }
      cache.statsCounter().recordMisses(proxies.size());
      cache.statsCounter().recordHits(futures.size() - proxies.size());
      if (proxies.isEmpty()) {
        return composeResult(futures);
      }

      loader.asyncLoadAll(proxies.keySet(), cache.executor())
          .whenComplete(new AsyncBulkCompleter(proxies));
      return composeResult(futures);
    }

    /**
     * Returns a future that waits for all of the dependent futures to complete and returns the
     * combined mapping if successful. If any future fails then it is automatically removed from
     * the cache if still present.
     */
    CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<V>> futures) {
      if (futures.isEmpty()) {
        return CompletableFuture.completedFuture(Collections.emptyMap());
      }
      CompletableFuture<?>[] array = futures.values().toArray(
          new CompletableFuture[futures.size()]);
      return CompletableFuture.allOf(array).thenApply(ignored -> {
        Map<K, V> result = new HashMap<>(futures.size());
        for (Entry<K, CompletableFuture<V>> entry : futures.entrySet()) {
          V value = entry.getValue().getNow(null);
          if (value != null) {
            result.put(entry.getKey(), value);
          }
        }
        return Collections.unmodifiableMap(result);
      });
    }

    @Override
    public void put(K key, CompletableFuture<V> valueFuture) {
      if (valueFuture.isCompletedExceptionally()) {
        cache.statsCounter().recordLoadFailure(0L);
        cache.remove(key);
        return;
      }
      long now = cache.ticker().read();
      cache.put(key, valueFuture);
      valueFuture.whenComplete((value, error) -> {
        long loadTime = cache.ticker().read() - now;
        if (value == null) {
          cache.remove(key, valueFuture);
          cache.statsCounter().recordLoadFailure(loadTime);
        } else {
          // update the weight and expiration timestamps
          cache.replace(key, valueFuture, valueFuture);
          cache.statsCounter().recordLoadSuccess(loadTime);
        }
      });
    }

    @Override
    public LoadingCache<K, V> synchronous() {
      return (localCacheView == null) ? (localCacheView = new LoadingCacheView()) : localCacheView;
    }

    /** A function executed asynchronously after a bulk load completes. */
    final class AsyncBulkCompleter implements BiConsumer<Map<K, V>, Throwable> {
      final Map<K, CompletableFuture<V>> proxies;
      final long now;

      AsyncBulkCompleter(Map<K, CompletableFuture<V>> proxies) {
        this.now = cache.ticker().read();
        this.proxies = proxies;
      }

      @Override
      public void accept(Map<K, V> result, Throwable error) {
        long loadTime = cache.ticker().read() - now;

        if (result == null) {
          if (error == null) {
            error = new CompletionException("null map", null);
          }
          for (Entry<K, CompletableFuture<V>> entry : proxies.entrySet()) {
            cache.remove(entry.getKey(), entry.getValue());
            entry.getValue().obtrudeException(error);
          }
          cache.statsCounter().recordLoadFailure(loadTime);
        } else {
          fillProxies(result);
          addNewEntries(result);
          cache.statsCounter().recordLoadSuccess(result.size());
        }
      }

      /** Populates the proxies with the computed result. */
      private void fillProxies(Map<K, V> result) {
        for (Entry<K, CompletableFuture<V>> proxy : proxies.entrySet()) {
          V value = result.get(proxy.getKey());
          proxy.getValue().obtrudeValue(value);
          if (value == null) {
            cache.remove(proxy.getKey(), proxy.getValue());
          } else {
            // update the weight and expiration timestamps
            cache.replace(proxy.getKey(), proxy.getValue(), proxy.getValue());
          }
        }
      }

      /** Adds to the cache any extra entries computed that were not requested. */
      private void addNewEntries(Map<K, V> result) {
        if (proxies.size() == result.size()) {
          return;
        }
        for (Entry<K, V> entry : result.entrySet()) {
          if (!proxies.containsKey(entry.getKey())) {
            cache.put(entry.getKey(), CompletableFuture.completedFuture(entry.getValue()));
          }
        }
      }
    }

    /* ---------------- Synchronous views -------------- */

    final class LoadingCacheView implements LoadingCache<K, V>, Serializable {
      private static final long serialVersionUID = 1L;

      transient AsMapView<K, V> asMapView;

      /** A test-only method for validation. */
      AsyncLocalLoadingCache<C, K, V> getOuter() {
        return AsyncLocalLoadingCache.this;
      }

      @Override
      public V getIfPresent(Object key) {
        CompletableFuture<V> future = cache.getIfPresent(key, true);
        return Shared.getIfReady(future);
      }

      @Override
      public Map<K, V> getAllPresent(Iterable<?> keys) {
        int hits = 0;
        int misses = 0;
        Map<K, V> result = new LinkedHashMap<>();
        for (Object key : keys) {
          CompletableFuture<V> future = cache.get(key);
          V value = Shared.getIfReady(future);
          if (value == null) {
            misses++;
          } else {
            hits++;
            @SuppressWarnings("unchecked")
            K castKey = (K) key;
            result.put(castKey, value);
          }
        }
        cache.statsCounter().recordHits(hits);
        cache.statsCounter().recordMisses(misses);
        return Collections.unmodifiableMap(result);
      }

      @Override
      public V get(K key, Function<? super K, ? extends V> mappingFunction) {
        requireNonNull(mappingFunction);
        CompletableFuture<V> future = AsyncLocalLoadingCache.this.get(key, (k, executor) ->
            CompletableFuture.supplyAsync(() -> mappingFunction.apply(key), executor));
        try {
          return future.get();
        } catch (ExecutionException e) {
          if (e.getCause() instanceof RuntimeException) {
            throw (RuntimeException) e.getCause();
          } else if (e.getCause() instanceof Error) {
            throw (Error) e.getCause();
          }
          throw new CompletionException(e.getCause());
        } catch (InterruptedException e) {
          throw new CompletionException(e);
        }
      }

      @Override
      public V get(K key) {
        try {
          return AsyncLocalLoadingCache.this.get(key).get();
        } catch (ExecutionException e) {
          if (e.getCause() instanceof RuntimeException) {
            throw (RuntimeException) e.getCause();
          } else if (e.getCause() instanceof Error) {
            throw (Error) e.getCause();
          }
          throw new CompletionException(e.getCause());
        } catch (InterruptedException e) {
          throw new CompletionException(e);
        }
      }

      @Override
      public Map<K, V> getAll(Iterable<? extends K> keys) {
        try {
          return AsyncLocalLoadingCache.this.getAll(keys).get();
        } catch (ExecutionException e) {
          if (e.getCause() instanceof RuntimeException) {
            throw (RuntimeException) e.getCause();
          } else if (e.getCause() instanceof Error) {
            throw (Error) e.getCause();
          }
          throw new CompletionException(e.getCause());
        } catch (InterruptedException e) {
          throw new CompletionException(e);
        }
      }

      @Override
      public void put(K key, V value) {
        requireNonNull(value);
        cache.put(key, CompletableFuture.completedFuture(value));
      }

      @Override
      public void putAll(Map<? extends K, ? extends V> map) {
        for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
          put(entry.getKey(), entry.getValue());
        }
      }

      @Override
      public void invalidate(Object key) {
        cache.remove(key);
      }

      @Override
      public void invalidateAll(Iterable<?> keys) {
        cache.invalidateAll(keys);
      }

      @Override
      public void invalidateAll() {
        cache.clear();
      }

      @Override
      public long estimatedSize() {
        return cache.size();
      }

      @Override
      public CacheStats stats() {
        return cache.statsCounter().snapshot();
      }

      @Override
      public void cleanUp() {
        cache.cleanUp();
      }

      @Override
      public void refresh(K key) {
        requireNonNull(key);

        BiFunction<K, CompletableFuture<V>, CompletableFuture<V>> refreshFunction =
            (k, oldValueFuture) -> {
              V oldValue = null;
              try {
                oldValue = (oldValueFuture == null) ? null : oldValueFuture.get();
              } catch (InterruptedException e) {
                throw new CompletionException(e);
              } catch (ExecutionException e) {}
              V newValue = (oldValue == null) ? loader.load(key) : loader.reload(key, oldValue);
              return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
            };
        cache.executor().execute(() -> {
          try {
            cache.compute(key, refreshFunction, false, false);
          } catch (Throwable t) {
            logger.log(Level.WARNING, "Exception thrown during refresh", t);
          }
        });
      }

      @Override
      public Policy<K, V> policy() {
        return getOuter().policy();
      }

      @Override
      public ConcurrentMap<K, V> asMap() {
        if (asMapView == null) {
          asMapView = new Shared.AsMapView<K, V>(cache, cache.statsCounter(), cache.ticker());
        }
        return asMapView;
      }
    }
  }

  /* ---------------- Loading Cache -------------- */

  interface LocalLoadingCache<C extends LocalCache<K, V>, K, V>
      extends LocalManualCache<C, K, V>, LoadingCache<K, V> {
    static final Logger logger = Logger.getLogger(LocalLoadingCache.class.getName());

    default boolean hasLoadAll(CacheLoader<? super K, V> loader) {
      try {
        return !loader.getClass().getMethod("loadAll", Iterable.class).isDefault();
      } catch (NoSuchMethodException | SecurityException e) {
        logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
        return false;
      }
    }

    CacheLoader<? super K, V> loader();
    boolean hasBulkLoader();

    @Override
    default V get(K key) {
      return cache().computeIfAbsent(key, loader()::load);
    }

    @Override
    default Map<K, V> getAll(Iterable<? extends K> keys) {
      List<K> keysToLoad = new ArrayList<>();
      Map<K, V> result = new HashMap<>();
      for (K key : keys) {
        V value = cache().getIfPresent(key, false);
        if (value == null) {
          keysToLoad.add(key);
        } else {
          result.put(key, value);
        }
      }
      cache().statsCounter().recordHits(result.size());
      if (keysToLoad.isEmpty()) {
        return result;
      }
      bulkLoad(keysToLoad, result);
      return Collections.unmodifiableMap(result);
    }

    default void bulkLoad(List<K> keysToLoad, Map<K, V> result) {
      cache().statsCounter().recordMisses(keysToLoad.size());

      if (!hasBulkLoader()) {
        for (K key : keysToLoad) {
          V value = cache().compute(key, (k, v) -> loader().load(key), false, false);
          if (value != null) {
            result.put(key, value);
          }
        }
        return;
      }

      boolean success = false;
      long startTime = cache().ticker().read();
      try {
        @SuppressWarnings("unchecked")
        Map<K, V> loaded = (Map<K, V>) loader().loadAll(keysToLoad);
        cache().putAll(loaded);
        for (K key : keysToLoad) {
          V value = loaded.get(key);
          if (value != null) {
            result.put(key, value);
          }
        }
        success = !loaded.isEmpty();
      } finally {
        long loadTime = cache().ticker().read() - startTime;
        if (success) {
          cache().statsCounter().recordLoadSuccess(loadTime);
        } else {
          cache().statsCounter().recordLoadFailure(loadTime);
        }
      }
    }

    @Override
    default void refresh(K key) {
      requireNonNull(key);
      cache().executor().execute(() -> {
        try {
          BiFunction<? super K, ? super V, ? extends V> refreshFunction = (k, oldValue) ->
              (oldValue == null)  ? loader().load(key) : loader().reload(key, oldValue);
          cache().compute(key, refreshFunction, false, false);
        } catch (Throwable t) {
          logger.log(Level.WARNING, "Exception thrown during refresh", t);
        }
      });
    }
  }

  /* ---------------- Manual Cache -------------- */

  interface LocalManualCache<C extends LocalCache<K, V>, K, V>
      extends Cache<K, V> {

    @Override
    default long estimatedSize() {
      return cache().mappingCount();
    }

    @Override
    default void cleanUp() {
      cache().cleanUp();
    }

    @Override
    default @Nullable V getIfPresent(Object key) {
      return cache().getIfPresent(key, true);
    }

    @Override
    default V get(K key, Function<? super K, ? extends V> mappingFunction) {
      return cache().computeIfAbsent(key, mappingFunction);
    }

    @Override
    default Map<K, V> getAllPresent(Iterable<?> keys) {
      return cache().getAllPresent(keys);
    }

    @Override
    default void put(K key, V value) {
      cache().put(key, value);
    }

    @Override
    default void putAll(Map<? extends K, ? extends V> map) {
      cache().putAll(map);
    }

    @Override
    default void invalidate(Object key) {
      requireNonNull(key);
      cache().remove(key);
    }

    @Override
    default void invalidateAll() {
      cache().clear();
    }

    @Override
    default void invalidateAll(Iterable<?> keys) {
      cache().invalidateAll(keys);
    }

    @Override
    default CacheStats stats() {
      return cache().statsCounter().snapshot();
    }

    @Override
    default ConcurrentMap<K, V> asMap() {
      return cache();
    }

    C cache();
  }

  /* ---------------- asynchronous AsMap view -------------- */

  static final class AsMapView<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    final LocalCache<K, CompletableFuture<V>> delegate;
    final StatsCounter statsCounter;
    final Ticker ticker;

    Collection<V> values;
    Set<Entry<K, V>> entries;

    AsMapView(LocalCache<K, CompletableFuture<V>> delegate,
        StatsCounter statsCounter, Ticker ticker) {
      this.statsCounter = statsCounter;
      this.delegate = delegate;
      this.ticker = ticker;
    }

    @Override
    public boolean isEmpty() {
      return delegate.isEmpty();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public boolean containsKey(Object key) {
      return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      requireNonNull(value);

      for (CompletableFuture<V> valueFuture : delegate.values()) {
        if (value.equals(Shared.getIfReady(valueFuture))) {
          return true;
        }
      }
      return false;
    }

    @Override
    public V get(Object key) {
      return Shared.getIfReady(delegate.get(key));
    }

    @Override
    public V putIfAbsent(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> valueFuture =
          delegate.putIfAbsent(key, CompletableFuture.completedFuture(value));
      return Shared.getWhenSuccessful(valueFuture);
    }

    @Override
    public V put(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.put(key, CompletableFuture.completedFuture(value));
      return Shared.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public V remove(Object key) {
      CompletableFuture<V> oldValueFuture = delegate.remove(key);
      return Shared.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public V replace(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.replace(key, CompletableFuture.completedFuture(value));
      return Shared.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
      requireNonNull(oldValue);
      requireNonNull(newValue);
      CompletableFuture<V> oldValueFuture = delegate.get(key);
      return oldValue.equals(Shared.getIfReady(oldValueFuture))
          ? delegate.replace(key, oldValueFuture, CompletableFuture.completedFuture(newValue))
          : false;
    }

    @Override
    public boolean remove(Object key, Object value) {
      requireNonNull(key);
      if (value == null) {
        return false;
      }
      CompletableFuture<V> oldValueFuture = delegate.get(key);
      return value.equals(Shared.getIfReady(oldValueFuture))
          ? delegate.remove(key, oldValueFuture)
          : false;
    }

    @Override
    public void clear() {
      delegate.clear();
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);
      CompletableFuture<V> valueFuture = delegate.computeIfAbsent(key, k -> {
        V newValue = mappingFunction.apply(key);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return Shared.getWhenSuccessful(valueFuture);
    }

    @Override
    public V computeIfPresent(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);
      CompletableFuture<V> valueFuture = delegate.computeIfPresent(key, (k, oldValueFuture) -> {
        V oldValue = Shared.getWhenSuccessful(oldValueFuture);
        if (oldValue == null) {
          return null;
        }
        V newValue = remappingFunction.apply(key, oldValue);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return Shared.getWhenSuccessful(valueFuture);
    }

    @Override
    public V compute(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);
      long now = ticker.read();
      CompletableFuture<V> valueFuture = delegate.compute(key, (k, oldValueFuture) -> {
        V oldValue = Shared.getWhenSuccessful(oldValueFuture);
        V newValue = remappingFunction.apply(key, oldValue);
        long loadTime = ticker.read() - now;
        if (newValue == null) {
          statsCounter.recordLoadFailure(loadTime);
          return null;
        }
        statsCounter.recordLoadSuccess(loadTime);
        return CompletableFuture.completedFuture(newValue);
      }, false, true);
      return Shared.getWhenSuccessful(valueFuture);
    }

    @Override
    public V merge(K key, V value,
        BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
      requireNonNull(value);
      requireNonNull(remappingFunction);
      CompletableFuture<V> mergedValueFuture = delegate.merge(
          key, CompletableFuture.completedFuture(value), (oldValueFuture, valueFuture) -> {
        V oldValue = Shared.getWhenSuccessful(oldValueFuture);
        if (oldValue == null) {
          return valueFuture;
        }
        V newValue = remappingFunction.apply(oldValue, value);
        return (newValue == null) ? null : CompletableFuture.completedFuture(newValue);
      });
      return Shared.getWhenSuccessful(mergedValueFuture);
    }

    @Override
    public Set<K> keySet() {
      return delegate.keySet();
    }

    @Override
    public Collection<V> values() {
      return (values == null) ? (values = new Values()) : values;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      return (entries == null) ? (entries = new EntrySet()) : entries;
    }

    final class Values extends AbstractCollection<V> {

      @Override
      public boolean isEmpty() {
        return AsMapView.this.isEmpty();
      }

      @Override
      public int size() {
        return AsMapView.this.size();
      }

      @Override
      public boolean contains(Object o) {
        return AsMapView.this.containsValue(o);
      }

      @Override
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public Iterator<V> iterator() {
        return new Iterator<V>() {
          Iterator<CompletableFuture<V>> iterator = delegate.values().iterator();
          V cursor;

          @Override
          public boolean hasNext() {
            while ((cursor == null) && iterator.hasNext()) {
              CompletableFuture<V> future = iterator.next();
              cursor = Shared.getIfReady(future);
            }
            return (cursor != null);
          }

          @Override
          public V next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            V value = cursor;
            cursor = null;
            return value;
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
    }

    final class EntrySet extends AbstractSet<Entry<K, V>> {

      @Override
      public boolean isEmpty() {
        return AsMapView.this.isEmpty();
      }

      @Override
      public int size() {
        return AsMapView.this.size();
      }

      @Override
      public boolean contains(Object o) {
        if (!(o instanceof Entry<?, ?>)) {
          return false;
        }
        Entry<?, ?> entry = (Entry<?, ?>) o;
        V value = AsMapView.this.get(entry.getKey());
        return (value != null) && value.equals(entry.getValue());
      }

      @Override
      public boolean add(Entry<K, V> entry) {
        return (AsMapView.this.putIfAbsent(entry.getKey(), entry.getValue()) == null);
      }

      @Override
      public boolean remove(Object obj) {
        if (!(obj instanceof Entry<?, ?>)) {
          return false;
        }
        Entry<?, ?> entry = (Entry<?, ?>) obj;
        return AsMapView.this.remove(entry.getKey(), entry.getValue());
      }

      @Override
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public Iterator<Entry<K, V>> iterator() {
        return new Iterator<Entry<K, V>>() {
          Iterator<Entry<K, CompletableFuture<V>>> iterator = delegate.entrySet().iterator();
          Entry<K, V> cursor;

          @Override
          public boolean hasNext() {
            while ((cursor == null) && iterator.hasNext()) {
              Entry<K, CompletableFuture<V>> entry = iterator.next();
              V value = Shared.getIfReady(entry.getValue());
              if (value != null) {
                cursor = new Shared.WriteThroughEntry<>(AsMapView.this, entry.getKey(), value);
              }
            }
            return (cursor != null);
          }

          @Override
          public Entry<K, V> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            Entry<K, V> entry = cursor;
            cursor = null;
            return entry;
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
    }
  }

  /** An entry that allows updates to write through to the cache. */
  static final class WriteThroughEntry<K, V> extends SimpleEntry<K, V> {
    static final long serialVersionUID = 1;

    transient final ConcurrentMap<K, V> map;

    WriteThroughEntry(ConcurrentMap<K, V> map, K key, V value) {
      super(key, value);
      this.map = requireNonNull(map);
    }

    @Override
    public V setValue(V value) {
      map.put(getKey(), value);
      return super.setValue(value);
    }

    @Override
    public boolean equals(Object o) {
      // suppress Findbugs warning
      return super.equals(o);
    }

    Object writeReplace() {
      return new SimpleEntry<K, V>(this);
    }
  }
}
