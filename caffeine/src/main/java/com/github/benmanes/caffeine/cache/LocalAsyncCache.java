/*
 * Copyright 2018 Ben Manes. All Rights Reserved.
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
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.LocalAsyncCache.AsyncBulkCompleter.NullMapCompletionException;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * This class provides a skeletal implementation of the {@link AsyncCache} interface to minimize the
 * effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalAsyncCache<K, V> extends AsyncCache<K, V> {
  Logger logger = Logger.getLogger(LocalAsyncCache.class.getName());

  /** Returns the backing {@link LocalCache} data store. */
  LocalCache<K, CompletableFuture<V>> cache();

  /** Returns the policy supported by this implementation and its configuration. */
  Policy<K, V> policy();

  @Override
  default @Nullable CompletableFuture<V> getIfPresent(@NonNull Object key) {
    return cache().getIfPresent(key, /* recordStats */ true);
  }

  @Override
  default CompletableFuture<V> get(@NonNull K key,
      @NonNull Function<? super K, ? extends V> mappingFunction) {
    requireNonNull(mappingFunction);
    return get(key, (k1, executor) -> CompletableFuture.supplyAsync(
        () -> mappingFunction.apply(key), executor));
  }

  @Override
  default CompletableFuture<V> get(K key,
      BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction) {
    return get(key, mappingFunction, /* recordStats */ true);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "NullAway"})
  default CompletableFuture<V> get(K key,
      BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction, boolean recordStats) {
    long startTime = cache().statsTicker().read();
    @SuppressWarnings({"unchecked", "rawtypes"})
    CompletableFuture<V>[] result = new CompletableFuture[1];
    CompletableFuture<V> future = cache().computeIfAbsent(key, k -> {
      result[0] = mappingFunction.apply(key, cache().executor());
      return requireNonNull(result[0]);
    }, recordStats, /* recordLoad */ false);
    if (result[0] != null) {
      handleCompletion(key, result[0], startTime, /* recordMiss */ false);
    }
    return future;
  }

  @Override
  default CompletableFuture<Map<K, V>> getAll(Iterable<? extends @NonNull K> keys,
      Function<Iterable<? extends K>, Map<K, V>> mappingFunction) {
    requireNonNull(mappingFunction);
    return getAll(keys, (keysToLoad, executor) ->
        CompletableFuture.supplyAsync(() -> mappingFunction.apply(keysToLoad), executor));
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  default CompletableFuture<Map<K, V>> getAll(Iterable<? extends @NonNull K> keys,
      BiFunction<Iterable<? extends K>, Executor, CompletableFuture<Map<K, V>>> mappingFunction) {
    requireNonNull(mappingFunction);
    requireNonNull(keys);

    Map<K, CompletableFuture<V>> futures = new LinkedHashMap<>();
    Map<K, CompletableFuture<V>> proxies = new HashMap<>();
    for (K key : keys) {
      if (futures.containsKey(key)) {
        continue;
      }
      CompletableFuture<V> future = cache().getIfPresent(key, /* recordStats */ false);
      if (future == null) {
        CompletableFuture<V> proxy = new CompletableFuture<>();
        future = cache().putIfAbsent(key, proxy);
        if (future == null) {
          future = proxy;
          proxies.put(key, proxy);
        }
      }
      futures.put(key, future);
    }
    cache().statsCounter().recordMisses(proxies.size());
    cache().statsCounter().recordHits(futures.size() - proxies.size());
    if (proxies.isEmpty()) {
      return composeResult(futures);
    }

    AsyncBulkCompleter<K, V> completer = new AsyncBulkCompleter<>(cache(), proxies);
    try {
      mappingFunction.apply(proxies.keySet(), cache().executor()).whenComplete(completer);
      return composeResult(futures);
    } catch (Throwable t) {
      completer.accept(/* result */ null, t);
      throw t;
    }
  }

  /**
   * Returns a future that waits for all of the dependent futures to complete and returns the
   * combined mapping if successful. If any future fails then it is automatically removed from
   * the cache if still present.
   */
  default CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<V>> futures) {
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

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  default void put(K key, CompletableFuture<V> valueFuture) {
    if (valueFuture.isCompletedExceptionally()
        || (valueFuture.isDone() && (valueFuture.join() == null))) {
      cache().statsCounter().recordLoadFailure(0L);
      cache().remove(key);
      return;
    }
    long startTime = cache().statsTicker().read();
    cache().put(key, valueFuture);
    handleCompletion(key, valueFuture, startTime, /* recordMiss */ false);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  default void handleCompletion(K key, CompletableFuture<V> valueFuture,
      long startTime, boolean recordMiss) {
    AtomicBoolean completed = new AtomicBoolean();
    valueFuture.whenComplete((value, error) -> {
      if (!completed.compareAndSet(false, true)) {
        // Ignore multiple invocations due to ForkJoinPool retrying on delays
        return;
      }
      long loadTime = cache().statsTicker().read() - startTime;
      if (value == null) {
        if (error != null) {
          logger.log(Level.WARNING, "Exception thrown during asynchronous load", error);
        }
        cache().remove(key, valueFuture);
        cache().statsCounter().recordLoadFailure(loadTime);
        if (recordMiss) {
          cache().statsCounter().recordMisses(1);
        }
      } else {
        // update the weight and expiration timestamps
        cache().replace(key, valueFuture, valueFuture);
        cache().statsCounter().recordLoadSuccess(loadTime);
        if (recordMiss) {
          cache().statsCounter().recordMisses(1);
        }
      }
    });
  }

  /** A function executed asynchronously after a bulk load completes. */
  final class AsyncBulkCompleter<K, V> implements BiConsumer<Map<K, V>, Throwable> {
    private final LocalCache<K, CompletableFuture<V>> cache;
    private final Map<K, CompletableFuture<V>> proxies;
    private final long startTime;

    AsyncBulkCompleter(LocalCache<K, CompletableFuture<V>> cache,
        Map<K, CompletableFuture<V>> proxies) {
      this.startTime = cache.statsTicker().read();
      this.proxies = proxies;
      this.cache = cache;
    }

    @Override
    public void accept(@Nullable Map<K, V> result, @Nullable Throwable error) {
      long loadTime = cache.statsTicker().read() - startTime;

      if (result == null) {
        if (error == null) {
          error = new NullMapCompletionException();
        }
        for (Map.Entry<K, CompletableFuture<V>> entry : proxies.entrySet()) {
          cache.remove(entry.getKey(), entry.getValue());
          entry.getValue().obtrudeException(error);
        }
        cache.statsCounter().recordLoadFailure(loadTime);
        logger.log(Level.WARNING, "Exception thrown during asynchronous load", error);
      } else {
        fillProxies(result);
        addNewEntries(result);
        cache.statsCounter().recordLoadSuccess(loadTime);
      }
    }

    /** Populates the proxies with the computed result. */
    private void fillProxies(Map<K, V> result) {
      proxies.forEach((key, future) -> {
        V value = result.get(key);
        future.obtrudeValue(value);
        if (value == null) {
          cache.remove(key, future);
        } else {
          // update the weight and expiration timestamps
          cache.replace(key, future, future);
        }
      });
    }

    /** Adds to the cache any extra entries computed that were not requested. */
    private void addNewEntries(Map<K, V> result) {
      if (proxies.size() == result.size()) {
        return;
      }
      result.forEach((key, value) -> {
        if (!proxies.containsKey(key)) {
          cache.put(key, CompletableFuture.completedFuture(value));
        }
      });
    }

    static final class NullMapCompletionException extends CompletionException {
      private static final long serialVersionUID = 1L;

      public NullMapCompletionException() {
        super("null map", null);
      }
    }
  }

  /* --------------- Asynchronous view --------------- */
  final class AsyncAsMapView<K, V> implements ConcurrentMap<K, CompletableFuture<V>> {
    final LocalAsyncCache<K, V> asyncCache;

    AsyncAsMapView(LocalAsyncCache<K, V> asyncCache) {
      this.asyncCache = requireNonNull(asyncCache);
    }
    @Override public boolean isEmpty() {
      return asyncCache.cache().isEmpty();
    }
    @Override public int size() {
      return asyncCache.cache().size();
    }
    @Override public void clear() {
      asyncCache.cache().clear();
    }
    @Override public boolean containsKey(Object key) {
      return asyncCache.cache().containsKey(key);
    }
    @Override public boolean containsValue(Object value) {
      return asyncCache.cache().containsValue(value);
    }
    @Override public @Nullable CompletableFuture<V> get(Object key) {
      return asyncCache.cache().get(key);
    }
    @Override public CompletableFuture<V> putIfAbsent(K key, CompletableFuture<V> value) {
      CompletableFuture<V> prior = asyncCache.cache().putIfAbsent(key, value);
      long startTime = asyncCache.cache().statsTicker().read();
      if (prior == null) {
        asyncCache.handleCompletion(key, value, startTime, /* recordMiss */ false);
      }
      return prior;
    }
    @Override public CompletableFuture<V> put(K key, CompletableFuture<V> value) {
      CompletableFuture<V> prior = asyncCache.cache().put(key, value);
      long startTime = asyncCache.cache().statsTicker().read();
      asyncCache.handleCompletion(key, value, startTime, /* recordMiss */ false);
      return prior;
    }
    @SuppressWarnings("FutureReturnValueIgnored")
    @Override public void putAll(Map<? extends K, ? extends CompletableFuture<V>> map) {
      map.forEach(this::put);
    }
    @Override public CompletableFuture<V> replace(K key, CompletableFuture<V> value) {
      CompletableFuture<V> prior = asyncCache.cache().replace(key, value);
      long startTime = asyncCache.cache().statsTicker().read();
      if (prior != null) {
        asyncCache.handleCompletion(key, value, startTime, /* recordMiss */ false);
      }
      return prior;
    }
    @Override
    public boolean replace(K key, CompletableFuture<V> oldValue, CompletableFuture<V> newValue) {
      boolean replaced = asyncCache.cache().replace(key, oldValue, newValue);
      long startTime = asyncCache.cache().statsTicker().read();
      if (replaced) {
        asyncCache.handleCompletion(key, newValue, startTime, /* recordMiss */ false);
      }
      return replaced;
    }
    @Override public CompletableFuture<V> remove(Object key) {
      return asyncCache.cache().remove(key);
    }
    @Override public boolean remove(Object key, Object value) {
      return asyncCache.cache().remove(key, value);
    }
    @SuppressWarnings("FutureReturnValueIgnored")
    @Override public @Nullable CompletableFuture<V> computeIfAbsent(K key,
        Function<? super K, ? extends CompletableFuture<V>> mappingFunction) {
      requireNonNull(mappingFunction);
      @SuppressWarnings({"rawtypes", "unchecked"})
      CompletableFuture<V>[] result = new CompletableFuture[1];
      long startTime = asyncCache.cache().statsTicker().read();
      CompletableFuture<V> future = asyncCache.cache().computeIfAbsent(key, k -> {
        result[0] = mappingFunction.apply(k);
        return result[0];
      }, /* recordStats */ false, /* recordLoad */ false);

      if (result[0] == null) {
        if ((future != null) && asyncCache.cache().isRecordingStats()) {
          future.whenComplete((r, e) -> {
            if ((r != null) || (e == null)) {
              asyncCache.cache().statsCounter().recordHits(1);
            }
          });
        }
      } else {
        asyncCache.handleCompletion(key, result[0], startTime, /* recordMiss */ true);
      }
      return future;
    }
    @Override public CompletableFuture<V> computeIfPresent(K key, BiFunction<? super K,
        ? super CompletableFuture<V>, ? extends CompletableFuture<V>> remappingFunction) {
      requireNonNull(remappingFunction);

      @SuppressWarnings({"rawtypes", "unchecked"})
      CompletableFuture<V>[] result = new CompletableFuture[1];
      long startTime = asyncCache.cache().statsTicker().read();
      asyncCache.cache().compute(key, (k, oldValue) -> {
        result[0] = (oldValue == null) ? null : remappingFunction.apply(k, oldValue);
        return result[0];
      }, /* recordMiss */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

      if (result[0] != null) {
        asyncCache.handleCompletion(key, result[0], startTime, /* recordMiss */ false);
      }
      return result[0];
    }
    @Override public CompletableFuture<V> compute(K key, BiFunction<? super K,
        ? super CompletableFuture<V>, ? extends CompletableFuture<V>> remappingFunction) {
      requireNonNull(remappingFunction);

      @SuppressWarnings({"rawtypes", "unchecked"})
      CompletableFuture<V>[] result = new CompletableFuture[1];
      long startTime = asyncCache.cache().statsTicker().read();
      asyncCache.cache().compute(key, (k, oldValue) -> {
        result[0] = remappingFunction.apply(k, oldValue);
        return result[0];
      }, /* recordMiss */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

      if (result[0] != null) {
        asyncCache.handleCompletion(key, result[0], startTime, /* recordMiss */ false);
      }
      return result[0];
    }
    @Override public CompletableFuture<V> merge(K key, CompletableFuture<V> value,
        BiFunction<? super CompletableFuture<V>, ? super CompletableFuture<V>,
            ? extends CompletableFuture<V>> remappingFunction) {
      requireNonNull(value);
      requireNonNull(remappingFunction);

      @SuppressWarnings({"rawtypes", "unchecked"})
      CompletableFuture<V>[] result = new CompletableFuture[1];
      long startTime = asyncCache.cache().statsTicker().read();
      asyncCache.cache().compute(key, (k, oldValue) -> {
        result[0] = (oldValue == null) ? value : remappingFunction.apply(oldValue, value);
        return result[0];
      }, /* recordMiss */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

      if (result[0] != null) {
        asyncCache.handleCompletion(key, result[0], startTime, /* recordMiss */ false);
      }
      return result[0];
    }
    @Override public Set<K> keySet() {
      return asyncCache.cache().keySet();
    }
    @Override public Collection<CompletableFuture<V>> values() {
      return asyncCache.cache().values();
    }
    @Override public Set<Entry<K, CompletableFuture<V>>> entrySet() {
      return asyncCache.cache().entrySet();
    }
    @Override public boolean equals(Object o) {
      return asyncCache.cache().equals(o);
    }
    @Override public int hashCode() {
      return asyncCache.cache().hashCode();
    }
    @Override public String toString() {
      return asyncCache.cache().toString();
    }
  }

  /* --------------- Synchronous view --------------- */
  final class CacheView<K, V> extends AbstractCacheView<K, V> {
    private static final long serialVersionUID = 1L;

    final LocalAsyncCache<K, V> asyncCache;

    CacheView(LocalAsyncCache<K, V> asyncCache) {
      this.asyncCache = requireNonNull(asyncCache);
    }
    @Override LocalAsyncCache<K, V> asyncCache() {
      return asyncCache;
    }
  }

  @SuppressWarnings("serial")
  abstract class AbstractCacheView<K, V> implements Cache<K, V>, Serializable {
    transient @Nullable AsMapView<K, V> asMapView;

    abstract LocalAsyncCache<K, V> asyncCache();

    @Override
    public @Nullable V getIfPresent(Object key) {
      CompletableFuture<V> future = asyncCache().cache().getIfPresent(key, /* recordStats */ true);
      return Async.getIfReady(future);
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<?> keys) {
      Set<Object> uniqueKeys = new LinkedHashSet<>();
      for (Object key : keys) {
        uniqueKeys.add(key);
      }

      int misses = 0;
      Map<Object, Object> result = new LinkedHashMap<>();
      for (Object key : uniqueKeys) {
        CompletableFuture<V> future = asyncCache().cache().get(key);
        Object value = Async.getIfReady(future);
        if (value == null) {
          misses++;
        } else {
          result.put(key, value);
        }
      }
      asyncCache().cache().statsCounter().recordMisses(misses);
      asyncCache().cache().statsCounter().recordHits(result.size());

      @SuppressWarnings("unchecked")
      Map<K, V> castedResult = (Map<K, V>) result;
      return Collections.unmodifiableMap(castedResult);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      return resolve(asyncCache().get(key, mappingFunction));
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys,
        Function<Iterable<? extends K>, Map<K, V>> mappingFunction) {
      return resolve(asyncCache().getAll(keys, mappingFunction));
    }

    @SuppressWarnings({"PMD.AvoidThrowingNullPointerException", "PMD.PreserveStackTrace"})
    protected static <T> T resolve(CompletableFuture<T> future) throws Error {
      try {
        return future.get();
      } catch (ExecutionException e) {
        if (e.getCause() instanceof NullMapCompletionException) {
          throw new NullPointerException(e.getCause().getMessage());
        } else if (e.getCause() instanceof RuntimeException) {
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
      asyncCache().cache().put(key, CompletableFuture.completedFuture(value));
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
      map.forEach(this::put);
    }

    @Override
    public void invalidate(Object key) {
      asyncCache().cache().remove(key);
    }

    @Override
    public void invalidateAll(Iterable<?> keys) {
      asyncCache().cache().invalidateAll(keys);
    }

    @Override
    public void invalidateAll() {
      asyncCache().cache().clear();
    }

    @Override
    public long estimatedSize() {
      return asyncCache().cache().size();
    }

    @Override
    public CacheStats stats() {
      return asyncCache().cache().statsCounter().snapshot();
    }

    @Override
    public void cleanUp() {
      asyncCache().cache().cleanUp();
    }

    @Override
    public Policy<K, V> policy() {
      return asyncCache().policy();
    }

    @Override
    public ConcurrentMap<K, V> asMap() {
      return (asMapView == null) ? (asMapView = new AsMapView<>(asyncCache().cache())) : asMapView;
    }
  }

  final class AsMapView<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V> {
    final LocalCache<K, CompletableFuture<V>> delegate;

    @Nullable Collection<V> values;
    @Nullable Set<Entry<K, V>> entries;

    AsMapView(LocalCache<K, CompletableFuture<V>> delegate) {
      this.delegate = delegate;
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
    public void clear() {
      delegate.clear();
    }

    @Override
    public boolean containsKey(Object key) {
      return delegate.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
      requireNonNull(value);

      for (CompletableFuture<V> valueFuture : delegate.values()) {
        if (value.equals(Async.getIfReady(valueFuture))) {
          return true;
        }
      }
      return false;
    }

    @Override
    public @Nullable V get(Object key) {
      return Async.getIfReady(delegate.get(key));
    }

    @Override
    public @Nullable V putIfAbsent(K key, V value) {
      requireNonNull(value);

      for (;;) {
        CompletableFuture<V> priorFuture = delegate.get(key);
        if (priorFuture != null) {
          if (!priorFuture.isDone()) {
            Async.getWhenSuccessful(priorFuture);
            continue;
          }

          V prior = Async.getWhenSuccessful(priorFuture);
          if (prior != null) {
            return prior;
          }
        }

        boolean[] added = { false };
        CompletableFuture<V> computed = delegate.compute(key, (k, valueFuture) -> {
          added[0] = (valueFuture == null)
              || (valueFuture.isDone() && (Async.getIfReady(valueFuture) == null));
          return added[0] ? CompletableFuture.completedFuture(value) : valueFuture;
        }, /* recordMiss */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

        if (added[0]) {
          return null;
        } else {
          V prior = Async.getWhenSuccessful(computed);
          if (prior != null) {
            return prior;
          }
        }
      }
    }

    @Override
    public @Nullable V put(K key, V value) {
      requireNonNull(value);
      CompletableFuture<V> oldValueFuture =
          delegate.put(key, CompletableFuture.completedFuture(value));
      return Async.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public @Nullable V remove(Object key) {
      CompletableFuture<V> oldValueFuture = delegate.remove(key);
      return Async.getWhenSuccessful(oldValueFuture);
    }

    @Override
    public boolean remove(Object key, Object value) {
      requireNonNull(key);
      if (value == null) {
        return false;
      }

      @SuppressWarnings("unchecked")
      K castedKey = (K) key;
      boolean[] done = { false };
      boolean[] removed = { false };
      for (;;) {
        CompletableFuture<V> future = delegate.get(key);
        if ((future == null) || future.isCompletedExceptionally()) {
          return false;
        }

        Async.getWhenSuccessful(future);
        delegate.compute(castedKey, (k, oldValueFuture) -> {
          if (oldValueFuture == null) {
            done[0] = true;
            return null;
          } else if (!oldValueFuture.isDone()) {
            return oldValueFuture;
          }

          done[0] = true;
          V oldValue = Async.getIfReady(oldValueFuture);
          removed[0] = value.equals(oldValue);
          return (oldValue == null) || removed[0] ? null : oldValueFuture;
        }, /* recordStats */ false, /* recordLoad */ false, /* recordLoadFailure */ true);

        if (done[0]) {
          return removed[0];
        }
      }
    }

    @Override
    public @Nullable V replace(K key, V value) {
      requireNonNull(value);

      @SuppressWarnings({"unchecked", "rawtypes"})
      V[] oldValue = (V[]) new Object[1];
      boolean[] done = { false };
      for (;;) {
        CompletableFuture<V> future = delegate.get(key);
        if ((future == null) || future.isCompletedExceptionally()) {
          return null;
        }

        Async.getWhenSuccessful(future);
        delegate.compute(key, (k, oldValueFuture) -> {
          if (oldValueFuture == null) {
            done[0] = true;
            return null;
          } else if (!oldValueFuture.isDone()) {
            return oldValueFuture;
          }

          done[0] = true;
          oldValue[0] = Async.getIfReady(oldValueFuture);
          return (oldValue[0] == null) ? null : CompletableFuture.completedFuture(value);
        }, /* recordStats */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

        if (done[0]) {
          return oldValue[0];
        }
      }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
      requireNonNull(oldValue);
      requireNonNull(newValue);

      boolean[] done = { false };
      boolean[] replaced = { false };
      for (;;) {
        CompletableFuture<V> future = delegate.get(key);
        if ((future == null) || future.isCompletedExceptionally()) {
          return false;
        }

        Async.getWhenSuccessful(future);
        delegate.compute(key, (k, oldValueFuture) -> {
          if (oldValueFuture == null) {
            done[0] = true;
            return null;
          } else if (!oldValueFuture.isDone()) {
            return oldValueFuture;
          }

          done[0] = true;
          replaced[0] = oldValue.equals(Async.getIfReady(oldValueFuture));
          return replaced[0] ? CompletableFuture.completedFuture(newValue) : oldValueFuture;
        }, /* recordStats */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

        if (done[0]) {
          return replaced[0];
        }
      }
    }

    @Override
    public @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);

      for (;;) {
        CompletableFuture<V> priorFuture = delegate.get(key);
        if (priorFuture != null) {
          if (!priorFuture.isDone()) {
            Async.getWhenSuccessful(priorFuture);
            continue;
          }

          V prior = Async.getWhenSuccessful(priorFuture);
          if (prior != null) {
            delegate.statsCounter().recordHits(1);
            return prior;
          }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        CompletableFuture<V>[] future = new CompletableFuture[1];
        CompletableFuture<V> computed = delegate.compute(key, (k, valueFuture) -> {
          if ((valueFuture != null) && valueFuture.isDone()
              && (Async.getIfReady(valueFuture) != null)) {
            return valueFuture;
          }

          V newValue = delegate.statsAware(mappingFunction, /* recordLoad */ true).apply(key);
          if (newValue == null) {
            return null;
          }
          future[0] = CompletableFuture.completedFuture(newValue);
          return future[0];
        }, /* recordMiss */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

        V result = Async.getWhenSuccessful(computed);
        if ((computed == future[0]) || (result != null)) {
          return result;
        }
      }
    }

    @Override
    public @Nullable V computeIfPresent(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);

      @SuppressWarnings({"unchecked", "rawtypes"})
      V[] newValue = (V[]) new Object[1];
      for (;;) {
        Async.getWhenSuccessful(delegate.get(key));

        CompletableFuture<V> valueFuture = delegate.computeIfPresent(key, (k, oldValueFuture) -> {
          if (!oldValueFuture.isDone()) {
            return oldValueFuture;
          }

          V oldValue = Async.getIfReady(oldValueFuture);
          if (oldValue == null) {
            return null;
          }

          newValue[0] = remappingFunction.apply(key, oldValue);
          return (newValue[0] == null) ? null : CompletableFuture.completedFuture(newValue[0]);
        });

        if (newValue[0] != null) {
          return newValue[0];
        } else if (valueFuture == null) {
          return null;
        }
      }
    }

    @Override
    public @Nullable V compute(K key,
        BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
      requireNonNull(remappingFunction);

      @SuppressWarnings({"unchecked", "rawtypes"})
      V[] newValue = (V[]) new Object[1];
      for (;;) {
        Async.getWhenSuccessful(delegate.get(key));

        CompletableFuture<V> valueFuture = delegate.compute(key, (k, oldValueFuture) -> {
          if ((oldValueFuture != null) && !oldValueFuture.isDone()) {
            return oldValueFuture;
          }

          V oldValue = Async.getIfReady(oldValueFuture);
          BiFunction<? super K, ? super V, ? extends V> function = delegate.statsAware(
              remappingFunction, /* recordMiss */ false, /* recordLoad */ true,
              /* recordLoadFailure */ true);
          newValue[0] = function.apply(key, oldValue);
          return (newValue[0] == null) ? null : CompletableFuture.completedFuture(newValue[0]);
        }, /* recordMiss */ false, /* recordLoad */ false, /* recordLoadFailure */ false);

        if (newValue[0] != null) {
          return newValue[0];
        } else if (valueFuture == null) {
          return null;
        }
      }
    }

    @Override
    public @Nullable V merge(K key, V value,
        BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
      requireNonNull(value);
      requireNonNull(remappingFunction);

      CompletableFuture<V> newValueFuture = CompletableFuture.completedFuture(value);
      boolean[] merged = { false };
      for (;;) {
        Async.getWhenSuccessful(delegate.get(key));

        CompletableFuture<V> mergedValueFuture = delegate.merge(
            key, newValueFuture, (oldValueFuture, valueFuture) -> {
          if ((oldValueFuture != null) && !oldValueFuture.isDone()) {
            return oldValueFuture;
          }

          merged[0] = true;
          V oldValue = Async.getIfReady(oldValueFuture);
          if (oldValue == null) {
            return valueFuture;
          }
          V mergedValue = remappingFunction.apply(oldValue, value);
          if (mergedValue == null) {
            return null;
          } else if (mergedValue == oldValue) {
            return oldValueFuture;
          } else if (mergedValue == value) {
            return valueFuture;
          }
          return CompletableFuture.completedFuture(mergedValue);
        });

        if (merged[0] || (mergedValueFuture == newValueFuture)) {
          return Async.getWhenSuccessful(mergedValueFuture);
        }
      }
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

    private final class Values extends AbstractCollection<V> {

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
          Iterator<Entry<K, V>> iterator = entrySet().iterator();

          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public V next() {
            return iterator.next().getValue();
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
    }

    private final class EntrySet extends AbstractSet<Entry<K, V>> {

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
          @Nullable Entry<K, V> cursor;
          @Nullable K removalKey;

          @Override
          public boolean hasNext() {
            while ((cursor == null) && iterator.hasNext()) {
              Entry<K, CompletableFuture<V>> entry = iterator.next();
              V value = Async.getIfReady(entry.getValue());
              if (value != null) {
                cursor = new WriteThroughEntry<>(AsMapView.this, entry.getKey(), value);
              }
            }
            return (cursor != null);
          }

          @Override
          public Entry<K, V> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            @SuppressWarnings("NullAway")
            K key = cursor.getKey();
            Entry<K, V> entry = cursor;
            removalKey = key;
            cursor = null;
            return entry;
          }

          @Override
          public void remove() {
            Caffeine.requireState(removalKey != null);
            delegate.remove(removalKey);
            removalKey = null;
          }
        };
      }
    }
  }
}
