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

import static com.github.benmanes.caffeine.cache.Caffeine.calculateHashMapCapacity;
import static com.github.benmanes.caffeine.cache.Caffeine.requireState;
import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.LocalAsyncCache.AsyncBulkCompleter.NullMapCompletionException;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

/**
 * This class provides a skeletal implementation of the {@link AsyncCache} interface to minimize the
 * effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalAsyncCache<K, V> extends AsyncCache<K, V> {
  Logger logger = System.getLogger(LocalAsyncCache.class.getName());

  /** Returns the backing {@link LocalCache} data store. */
  LocalCache<K, CompletableFuture<V>> cache();

  /** Returns the policy supported by this implementation and its configuration. */
  Policy<K, V> policy();

  @Override
  default @Nullable CompletableFuture<V> getIfPresent(K key) {
    return cache().getIfPresent(key, /* recordStats= */ true);
  }

  @Override
  default CompletableFuture<V> get(K key, Function<? super K, ? extends V> mappingFunction) {
    requireNonNull(mappingFunction);
    return get(key, (k1, executor) -> CompletableFuture.supplyAsync(
        () -> mappingFunction.apply(key), executor));
  }

  @Override
  default CompletableFuture<V> get(K key, BiFunction<? super K, ? super Executor,
      ? extends CompletableFuture<? extends V>> mappingFunction) {
    return get(key, mappingFunction, /* recordStats= */ true);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "NullAway"})
  default CompletableFuture<V> get(K key, BiFunction<? super K, ? super Executor,
      ? extends CompletableFuture<? extends V>> mappingFunction, boolean recordStats) {
    long startTime = cache().statsTicker().read();
    @SuppressWarnings({"rawtypes", "unchecked"})
    CompletableFuture<? extends V>[] result = new CompletableFuture[1];
    CompletableFuture<V> future = cache().computeIfAbsent(key, k -> {
      @SuppressWarnings("unchecked")
      var castedResult = (CompletableFuture<V>) mappingFunction.apply(key, cache().executor());
      result[0] = castedResult;
      return requireNonNull(castedResult);
    }, recordStats, /* recordLoad= */ false);
    if (result[0] != null) {
      handleCompletion(key, result[0], startTime, /* recordMiss= */ false);
    }
    return future;
  }

  @Override
  default CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys,
      Function<? super Set<? extends K>, ? extends Map<? extends K, ? extends V>> mappingFunction) {
    requireNonNull(mappingFunction);
    return getAll(keys, (keysToLoad, executor) ->
        CompletableFuture.supplyAsync(() -> mappingFunction.apply(keysToLoad), executor));
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  default CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys,
      BiFunction<? super Set<? extends K>, ? super Executor,
          ? extends CompletableFuture<? extends Map<? extends K, ? extends V>>> mappingFunction) {
    requireNonNull(mappingFunction);
    requireNonNull(keys);

    int initialCapacity = calculateHashMapCapacity(keys);
    var futures = new LinkedHashMap<K, CompletableFuture<V>>(initialCapacity);
    var proxies = new LinkedHashMap<K, CompletableFuture<V>>(initialCapacity);
    for (K key : keys) {
      if (futures.containsKey(key)) {
        continue;
      }
      @Var CompletableFuture<V> future = cache().getIfPresent(key, /* recordStats= */ false);
      if (future == null) {
        var proxy = new CompletableFuture<V>();
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

    var completer = new AsyncBulkCompleter<>(cache(), proxies);
    try {
      var loader = mappingFunction.apply(
          Collections.unmodifiableSet(proxies.keySet()), cache().executor());
      return loader.handle(completer).thenCompose(ignored -> composeResult(futures));
    } catch (Throwable t) {
      completer.apply(/* result= */ null, t);
      throw t;
    }
  }

  /**
   * Returns a future that waits for all of the dependent futures to complete and returns the
   * combined mapping if successful. If any future fails then it is automatically removed from
   * the cache if still present.
   */
  static <K, V> CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<V>> futures) {
    if (futures.isEmpty()) {
      @SuppressWarnings("ImmutableMapOf")
      Map<K, V> emptyMap = Collections.unmodifiableMap(Collections.emptyMap());
      return CompletableFuture.completedFuture(emptyMap);
    }
    @SuppressWarnings("rawtypes")
    CompletableFuture<?>[] array = futures.values().toArray(new CompletableFuture[0]);
    return CompletableFuture.allOf(array).thenApply(ignored -> {
      var result = new LinkedHashMap<K, V>(calculateHashMapCapacity(futures.size()));
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
  default void put(K key, CompletableFuture<? extends V> valueFuture) {
    if (valueFuture.isCompletedExceptionally()
        || (valueFuture.isDone() && (valueFuture.join() == null))) {
      cache().statsCounter().recordLoadFailure(0L);
      cache().remove(key);
      return;
    }
    long startTime = cache().statsTicker().read();

    @SuppressWarnings("unchecked")
    var castedFuture = (CompletableFuture<V>) valueFuture;
    cache().put(key, castedFuture);
    handleCompletion(key, valueFuture, startTime, /* recordMiss= */ false);
  }

  @SuppressWarnings({"FutureReturnValueIgnored", "SuspiciousMethodCalls"})
  default void handleCompletion(K key, CompletableFuture<? extends V> valueFuture,
      long startTime, boolean recordMiss) {
    var completed = new AtomicBoolean();
    valueFuture.whenComplete((value, error) -> {
      if (!completed.compareAndSet(/* expectedValue= */ false, /* newValue= */ true)) {
        // Ignore multiple invocations due to ForkJoinPool retrying on delays
        return;
      }
      long loadTime = cache().statsTicker().read() - startTime;
      if (value == null) {
        if ((error != null) && !(error instanceof CancellationException)
            && !(error instanceof TimeoutException)) {
          logger.log(Level.WARNING, "Exception thrown during asynchronous load", error);
        }
        cache().statsCounter().recordLoadFailure(loadTime);
        cache().remove(key, valueFuture);
      } else {
        @SuppressWarnings("unchecked")
        var castedFuture = (CompletableFuture<V>) valueFuture;

        try {
          // update the weight and expiration timestamps
          cache().replace(key, castedFuture, castedFuture, /* shouldDiscardRefresh= */ false);
          cache().statsCounter().recordLoadSuccess(loadTime);
        } catch (Throwable t) {
          logger.log(Level.WARNING, "Exception thrown during asynchronous load", t);
          cache().statsCounter().recordLoadFailure(loadTime);
          cache().remove(key, valueFuture);
        }
      }
      if (recordMiss) {
        cache().statsCounter().recordMisses(1);
      }
    });
  }

  /** A function executed asynchronously after a bulk load completes. */
  final class AsyncBulkCompleter<K, V>
      implements BiFunction<Map<? extends K, ? extends V>, Throwable, Map<? extends K, ? extends V>> {
    private final LocalCache<K, CompletableFuture<V>> cache;
    @SuppressWarnings("ImmutableMemberCollection")
    private final Map<K, CompletableFuture<V>> proxies;
    private final long startTime;

    AsyncBulkCompleter(LocalCache<K, CompletableFuture<V>> cache,
        Map<K, CompletableFuture<V>> proxies) {
      this.startTime = cache.statsTicker().read();
      this.proxies = proxies;
      this.cache = cache;
    }

    @Override
    @CanIgnoreReturnValue
    public @Nullable Map<? extends K, ? extends V> apply(
        @Nullable Map<? extends K, ? extends V> result, @Nullable Throwable error) {
      long loadTime = cache.statsTicker().read() - startTime;
      var failure = handleResponse(result, error);

      if (failure == null) {
        cache.statsCounter().recordLoadSuccess(loadTime);
        return result;
      }

      cache.statsCounter().recordLoadFailure(loadTime);
      if (failure instanceof RuntimeException) {
        throw (RuntimeException) failure;
      } else if (failure instanceof Error) {
        throw (Error) failure;
      }
      throw new CompletionException(failure);
    }

    private @Nullable Throwable handleResponse(
        @Nullable Map<? extends K, ? extends V> result, @Nullable Throwable error) {
      if (result == null) {
        var failure = (error == null) ? new NullMapCompletionException() : error;
        for (var entry : proxies.entrySet()) {
          cache.remove(entry.getKey(), entry.getValue());
          entry.getValue().obtrudeException(failure);
        }
        if (!(failure instanceof CancellationException) && !(failure instanceof TimeoutException)) {
          logger.log(Level.WARNING, "Exception thrown during asynchronous load", failure);
        }
        return failure;
      } else {
        var failure = fillProxies(result);
        return addNewEntries(result, failure);
      }
    }

    /** Populates the proxies with the computed result. */
    private @Nullable Throwable fillProxies(Map<? extends K, ? extends V> result) {
      @Var Throwable error = null;
      for (var entry : proxies.entrySet()) {
        var key = entry.getKey();
        var value = result.get(key);
        var future = entry.getValue();
        future.obtrudeValue(value);

        if (value == null) {
          cache.remove(key, future);
        } else {
          try {
            // update the weight and expiration timestamps
            cache.replace(key, future, future);
          } catch (Throwable t) {
            logger.log(Level.WARNING, "Exception thrown during asynchronous load", t);
            cache.remove(key, future);
            if (error == null) {
              error = t;
            } else {
              error.addSuppressed(t);
            }
          }
        }
      }
      return error;
    }

    /** Adds to the cache any extra entries computed that were not requested. */
    private @Nullable Throwable addNewEntries(
        Map<? extends K, ? extends V> result, @Nullable Throwable failure) {
      @Var Throwable error = failure;
      for (var entry : result.entrySet()) {
        var key = entry.getKey();
        var value = result.get(key);
        if (!proxies.containsKey(key)) {
          try {
            cache.put(key, CompletableFuture.completedFuture(value));
          } catch (Throwable t) {
            logger.log(Level.WARNING, "Exception thrown during asynchronous load", t);
            if (error == null) {
              error = t;
            } else {
              error.addSuppressed(t);
            }
          }
        }
      }
      return error;
    }

    static final class NullMapCompletionException extends CompletionException {
      private static final long serialVersionUID = 1L;
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
        asyncCache.handleCompletion(key, value, startTime, /* recordMiss= */ false);
      }
      return prior;
    }
    @Override public CompletableFuture<V> put(K key, CompletableFuture<V> value) {
      CompletableFuture<V> prior = asyncCache.cache().put(key, value);
      long startTime = asyncCache.cache().statsTicker().read();
      asyncCache.handleCompletion(key, value, startTime, /* recordMiss= */ false);
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
        asyncCache.handleCompletion(key, value, startTime, /* recordMiss= */ false);
      }
      return prior;
    }
    @Override
    public boolean replace(K key, CompletableFuture<V> oldValue, CompletableFuture<V> newValue) {
      boolean replaced = asyncCache.cache().replace(key, oldValue, newValue);
      long startTime = asyncCache.cache().statsTicker().read();
      if (replaced) {
        asyncCache.handleCompletion(key, newValue, startTime, /* recordMiss= */ false);
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
      }, /* recordStats= */ false, /* recordLoad= */ false);

      if (result[0] == null) {
        if ((future != null) && asyncCache.cache().isRecordingStats()) {
          future.whenComplete((r, e) -> {
            if ((r != null) || (e == null)) {
              asyncCache.cache().statsCounter().recordHits(1);
            }
          });
        }
      } else {
        asyncCache.handleCompletion(key, result[0], startTime, /* recordMiss= */ true);
      }
      return future;
    }
    @Override public @Nullable CompletableFuture<V> computeIfPresent(K key, BiFunction<? super K,
        ? super CompletableFuture<V>, ? extends CompletableFuture<V>> remappingFunction) {
      requireNonNull(remappingFunction);

      @SuppressWarnings({"rawtypes", "unchecked"})
      @Nullable CompletableFuture<V>[] result = new CompletableFuture[1];
      long startTime = asyncCache.cache().statsTicker().read();
      asyncCache.cache().compute(key, (k, oldValue) -> {
        result[0] = (oldValue == null) ? null : remappingFunction.apply(k, oldValue);
        return result[0];
      }, asyncCache.cache().expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ false);

      if (result[0] != null) {
        asyncCache.handleCompletion(key, result[0], startTime, /* recordMiss= */ false);
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
      }, asyncCache.cache().expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ false);

      if (result[0] != null) {
        asyncCache.handleCompletion(key, result[0], startTime, /* recordMiss= */ false);
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
      }, asyncCache.cache().expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ false);

      if (result[0] != null) {
        asyncCache.handleCompletion(key, result[0], startTime, /* recordMiss= */ false);
      }
      return result[0];
    }
    @Override public void forEach(BiConsumer<? super K, ? super CompletableFuture<V>> action) {
      asyncCache.cache().forEach(action);
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
    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    @Override public boolean equals(@Nullable Object o) {
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

    @SuppressWarnings("serial")
    final LocalAsyncCache<K, V> asyncCache;

    CacheView(LocalAsyncCache<K, V> asyncCache) {
      this.asyncCache = requireNonNull(asyncCache);
    }
    @Override LocalAsyncCache<K, V> asyncCache() {
      return asyncCache;
    }
  }

  abstract class AbstractCacheView<K, V> implements Cache<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    transient @Nullable ConcurrentMap<K, V> asMapView;

    abstract LocalAsyncCache<K, V> asyncCache();

    @Override
    public @Nullable V getIfPresent(K key) {
      CompletableFuture<V> future = asyncCache().cache().getIfPresent(key, /* recordStats= */ true);
      return Async.getIfReady(future);
    }

    @Override
    public Map<K, V> getAllPresent(Iterable<? extends K> keys) {
      var result = new LinkedHashMap<K, V>(calculateHashMapCapacity(keys));
      for (K key : keys) {
        result.put(key, null);
      }

      int uniqueKeys = result.size();
      for (var iter = result.entrySet().iterator(); iter.hasNext();) {
        Map.Entry<K, V> entry = iter.next();

        CompletableFuture<V> future = asyncCache().cache().get(entry.getKey());
        V value = Async.getIfReady(future);
        if (value == null) {
          iter.remove();
        } else {
          entry.setValue(value);
        }
      }
      asyncCache().cache().statsCounter().recordHits(result.size());
      asyncCache().cache().statsCounter().recordMisses(uniqueKeys - result.size());

      return Collections.unmodifiableMap(result);
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> mappingFunction) {
      return resolve(asyncCache().get(key, mappingFunction));
    }

    @Override
    public Map<K, V> getAll(Iterable<? extends K> keys,
        Function<? super Set<? extends K>, ? extends Map<? extends K, ? extends V>> mappingFunction) {
      return resolve(asyncCache().getAll(keys, mappingFunction));
    }

    @SuppressWarnings({"PMD.AvoidThrowingNullPointerException",
      "PMD.PreserveStackTrace", "UnusedException"})
    protected static <T> T resolve(CompletableFuture<T> future) {
      try {
        return future.join();
      } catch (NullMapCompletionException e) {
        throw new NullPointerException("null map");
      } catch (CompletionException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        } else if (e.getCause() instanceof Error) {
          throw (Error) e.getCause();
        }
        throw e;
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
    public void invalidate(K key) {
      asyncCache().cache().remove(key);
    }

    @Override
    public void invalidateAll(Iterable<? extends K> keys) {
      asyncCache().cache().invalidateAll(keys);
    }

    @Override
    public void invalidateAll() {
      asyncCache().cache().clear();
    }

    @Override
    public long estimatedSize() {
      return asyncCache().cache().estimatedSize();
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

  final class AsMapView<K, V> implements ConcurrentMap<K, V> {
    final LocalCache<K, CompletableFuture<V>> delegate;

    @Nullable Set<K> keys;
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
      return Async.isReady(delegate.getIfPresentQuietly(key));
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

      // Keep in sync with BoundedVarExpiration.putIfAbsentAsync(key, value, duration, unit)
      @Var CompletableFuture<V> priorFuture = null;
      for (;;) {
        priorFuture = (priorFuture == null)
            ? delegate.get(key)
            : delegate.getIfPresentQuietly(key);
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
        }, delegate.expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ false);

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
    public void putAll(Map<? extends K, ? extends V> map) {
      map.forEach(this::put);
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
      var castedKey = (K) key;
      boolean[] done = { false };
      boolean[] removed = { false };
      @Var CompletableFuture<V> future = null;
      for (;;) {
        future = (future == null)
            ? delegate.get(castedKey)
            : delegate.getIfPresentQuietly(castedKey);
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
        }, delegate.expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ true);

        if (done[0]) {
          return removed[0];
        }
      }
    }

    @Override
    public @Nullable V replace(K key, V value) {
      requireNonNull(value);

      @SuppressWarnings({"rawtypes", "unchecked", "Varifier"})
      @Nullable V[] oldValue = (V[]) new Object[1];
      boolean[] done = { false };
      for (;;) {
        CompletableFuture<V> future = delegate.getIfPresentQuietly(key);
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
        }, delegate.expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ false);

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
        CompletableFuture<V> future = delegate.getIfPresentQuietly(key);
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
        }, delegate.expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ false);

        if (done[0]) {
          return replaced[0];
        }
      }
    }

    @Override
    public @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
      requireNonNull(mappingFunction);

      @Var CompletableFuture<V> priorFuture = null;
      for (;;) {
        priorFuture = (priorFuture == null)
            ? delegate.get(key)
            : delegate.getIfPresentQuietly(key);
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

        @SuppressWarnings({"rawtypes", "unchecked"})
        CompletableFuture<V>[] future = new CompletableFuture[1];
        CompletableFuture<V> computed = delegate.compute(key, (k, valueFuture) -> {
          if ((valueFuture != null) && valueFuture.isDone()
              && (Async.getIfReady(valueFuture) != null)) {
            return valueFuture;
          }

          V newValue = delegate.statsAware(mappingFunction, /* recordLoad= */ true).apply(key);
          if (newValue == null) {
            return null;
          }
          future[0] = CompletableFuture.completedFuture(newValue);
          return future[0];
        }, delegate.expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ false);

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

      @SuppressWarnings({"rawtypes", "unchecked"})
      var newValue = (V[]) new Object[1];
      for (;;) {
        Async.getWhenSuccessful(delegate.getIfPresentQuietly(key));

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
      // Keep in sync with BoundedVarExpiration.computeAsync(key, remappingFunction, expiry)
      requireNonNull(remappingFunction);

      @SuppressWarnings({"rawtypes", "unchecked"})
      var newValue = (V[]) new Object[1];
      for (;;) {
        Async.getWhenSuccessful(delegate.getIfPresentQuietly(key));

        CompletableFuture<V> valueFuture = delegate.compute(key, (k, oldValueFuture) -> {
          if ((oldValueFuture != null) && !oldValueFuture.isDone()) {
            return oldValueFuture;
          }

          V oldValue = Async.getIfReady(oldValueFuture);
          BiFunction<? super K, ? super V, ? extends V> function = delegate.statsAware(
              remappingFunction, /* recordLoad= */ true, /* recordLoadFailure= */ true);
          newValue[0] = function.apply(key, oldValue);
          return (newValue[0] == null) ? null : CompletableFuture.completedFuture(newValue[0]);
        }, delegate.expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ false);

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
        Async.getWhenSuccessful(delegate.getIfPresentQuietly(key));

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
      return (keys == null) ? (keys = new KeySet()) : keys;
    }

    @Override
    public Collection<V> values() {
      return (values == null) ? (values = new Values()) : values;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      return (entries == null) ? (entries = new EntrySet()) : entries;
    }

    /** See {@link BoundedLocalCache#equals(Object)} for semantics. */
    @Override
    public boolean equals(@Nullable Object o) {
      if (o == this) {
        return true;
      } else if (!(o instanceof Map)) {
        return false;
      }

      var map = (Map<?, ?>) o;
      int expectedSize = size();
      if (map.size() != expectedSize) {
        return false;
      }

      @Var int count = 0;
      for (var iterator = new EntryIterator(); iterator.hasNext();) {
        var entry = iterator.next();
        var value = map.get(entry.getKey());
        if ((value == null) || ((value != entry.getValue()) && !value.equals(entry.getValue()))) {
          return false;
        }
        count++;
      }
      return (count == expectedSize);
    }

    @Override
    public int hashCode() {
      @Var int hash = 0;
      for (var iterator = new EntryIterator(); iterator.hasNext();) {
        var entry = iterator.next();
        hash += entry.hashCode();
      }
      return hash;
    }

    @Override
    public String toString() {
      var result = new StringBuilder(50).append('{');
      for (var iterator = new EntryIterator(); iterator.hasNext();) {
        var entry = iterator.next();
        result.append((entry.getKey() == this) ? "(this Map)" : entry.getKey())
            .append('=')
            .append((entry.getValue() == this) ? "(this Map)" : entry.getValue());

        if (iterator.hasNext()) {
          result.append(", ");
        }
      }
      return result.append('}').toString();
    }

    private final class KeySet extends AbstractSet<K> {

      @Override
      public boolean isEmpty() {
        return AsMapView.this.isEmpty();
      }

      @Override
      public int size() {
        return AsMapView.this.size();
      }

      @Override
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public boolean contains(Object o) {
        return AsMapView.this.containsKey(o);
      }

      @Override
      public boolean removeAll(Collection<?> collection) {
        return delegate.keySet().removeAll(collection);
      }

      @Override
      public boolean remove(Object o) {
        return delegate.keySet().remove(o);
      }

      @Override
      public boolean removeIf(Predicate<? super K> filter) {
        return delegate.keySet().removeIf(filter);
      }

      @Override
      public boolean retainAll(Collection<?> collection) {
        return delegate.keySet().retainAll(collection);
      }

      @Override
      public Iterator<K> iterator() {
        return new Iterator<>() {
          final Iterator<Entry<K, V>> iterator = entrySet().iterator();

          @Override
          public boolean hasNext() {
            return iterator.hasNext();
          }

          @Override
          public K next() {
            return iterator.next().getKey();
          }

          @Override
          public void remove() {
            iterator.remove();
          }
        };
      }
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
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public boolean contains(Object o) {
        return AsMapView.this.containsValue(o);
      }

      @Override
      public boolean removeAll(Collection<?> collection) {
        requireNonNull(collection);
        @Var boolean modified = false;
        for (var entry : delegate.entrySet()) {
          V value = Async.getIfReady(entry.getValue());
          if ((value != null) && collection.contains(value)
              && AsMapView.this.remove(entry.getKey(), value)) {
            modified = true;
          }
        }
        return modified;
      }

      @Override
      public boolean remove(Object o) {
        if (o == null) {
          return false;
        }
        for (var entry : delegate.entrySet()) {
          V value = Async.getIfReady(entry.getValue());
          if ((value != null) && value.equals(o) && AsMapView.this.remove(entry.getKey(), value)) {
            return true;
          }
        }
        return false;
      }

      @Override
      public boolean removeIf(Predicate<? super V> filter) {
        requireNonNull(filter);
        return delegate.values().removeIf(future -> {
          V value = Async.getIfReady(future);
          return (value != null) && filter.test(value);
        });
      }

      @Override
      public boolean retainAll(Collection<?> collection) {
        requireNonNull(collection);
        @Var boolean modified = false;
        for (var entry : delegate.entrySet()) {
          V value = Async.getIfReady(entry.getValue());
          if ((value != null) && !collection.contains(value)
              && AsMapView.this.remove(entry.getKey(), value)) {
            modified = true;
          }
        }
        return modified;
      }

      @Override
      public void forEach(Consumer<? super V> action) {
        requireNonNull(action);
        delegate.values().forEach(future -> {
          V value = Async.getIfReady(future);
          if (value != null) {
            action.accept(value);
          }
        });
      }

      @Override
      public Iterator<V> iterator() {
        return new Iterator<>() {
          final Iterator<Entry<K, V>> iterator = entrySet().iterator();

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
      public void clear() {
        AsMapView.this.clear();
      }

      @Override
      public boolean contains(Object o) {
        if (!(o instanceof Entry<?, ?>)) {
          return false;
        }
        var entry = (Entry<?, ?>) o;
        var key = entry.getKey();
        var value = entry.getValue();
        if ((key == null) || (value == null)) {
          return false;
        }
        V cachedValue = AsMapView.this.get(key);
        return (cachedValue != null) && cachedValue.equals(value);
      }

      @Override
      public boolean removeAll(Collection<?> collection) {
        requireNonNull(collection);
        @Var boolean modified = false;
        if ((collection instanceof Set<?>) && (collection.size() > size())) {
          for (var entry : this) {
            if (collection.contains(entry)) {
              modified |= remove(entry);
            }
          }
        } else {
          for (var o : collection) {
            modified |= remove(o);
          }
        }
        return modified;
      }

      @Override
      public boolean remove(Object obj) {
        if (!(obj instanceof Entry<?, ?>)) {
          return false;
        }
        var entry = (Entry<?, ?>) obj;
        var key = entry.getKey();
        return (key != null) && AsMapView.this.remove(key, entry.getValue());
      }

      @Override
      public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
        requireNonNull(filter);
        @Var boolean modified = false;
        for (Entry<K, V> entry : this) {
          if (filter.test(entry)) {
            modified |= AsMapView.this.remove(entry.getKey(), entry.getValue());
          }
        }
        return modified;
      }

      @Override
      public boolean retainAll(Collection<?> collection) {
        requireNonNull(collection);
        @Var boolean modified = false;
        for (var entry : this) {
          if (!collection.contains(entry) && remove(entry)) {
            modified = true;
          }
        }
        return modified;
      }

      @Override
      public Iterator<Entry<K, V>> iterator() {
        return new EntryIterator();
      }
    }

    private final class EntryIterator implements Iterator<Entry<K, V>> {
      final Iterator<Entry<K, CompletableFuture<V>>> iterator;
      @Nullable Entry<K, V> cursor;
      @Nullable K removalKey;

      EntryIterator() {
        iterator = delegate.entrySet().iterator();
      }

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
        requireState(removalKey != null);
        delegate.remove(removalKey);
        removalKey = null;
      }
    }
  }
}
