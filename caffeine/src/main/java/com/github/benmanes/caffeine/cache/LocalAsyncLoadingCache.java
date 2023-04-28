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

import static com.github.benmanes.caffeine.cache.Caffeine.calculateHashMapCapacity;
import static com.github.benmanes.caffeine.cache.LocalAsyncCache.composeResult; // NOPMD
import static java.util.Objects.requireNonNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * This class provides a skeletal implementation of the {@link AsyncLoadingCache} interface to
 * minimize the effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class LocalAsyncLoadingCache<K, V>
    implements LocalAsyncCache<K, V>, AsyncLoadingCache<K, V> {
  static final Logger logger = System.getLogger(LocalAsyncLoadingCache.class.getName());

  final @Nullable BiFunction<? super Set<? extends K>, ? super Executor,
      ? extends CompletableFuture<? extends Map<? extends K, ? extends V>>> bulkMappingFunction;
  final BiFunction<? super K, ? super Executor,
      ? extends CompletableFuture<? extends V>> mappingFunction;
  final AsyncCacheLoader<K, V> cacheLoader;

  @Nullable LoadingCacheView<K, V> cacheView;

  @SuppressWarnings("unchecked")
  LocalAsyncLoadingCache(AsyncCacheLoader<? super K, V> cacheLoader) {
    this.bulkMappingFunction = newBulkMappingFunction(cacheLoader);
    this.cacheLoader = (AsyncCacheLoader<K, V>) cacheLoader;
    this.mappingFunction = newMappingFunction(cacheLoader);
  }

  /** Returns a mapping function that adapts to {@link AsyncCacheLoader#asyncLoad}. */
  BiFunction<? super K, ? super Executor, ? extends CompletableFuture<? extends V>> newMappingFunction(
      AsyncCacheLoader<? super K, V> cacheLoader) {
    return (key, executor) -> {
      try {
        return cacheLoader.asyncLoad(key, executor);
      } catch (RuntimeException e) {
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CompletionException(e);
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    };
  }

  /**
   * Returns a mapping function that adapts to {@link AsyncCacheLoader#asyncLoadAll}, if
   * implemented.
   */
  @Nullable
  BiFunction<Set<? extends K>, Executor, CompletableFuture<Map<K, V>>> newBulkMappingFunction(
      AsyncCacheLoader<? super K, V> cacheLoader) {
    if (!canBulkLoad(cacheLoader)) {
      return null;
    }
    return (keysToLoad, executor) -> {
      try {
        @SuppressWarnings("unchecked")
        var loaded = (CompletableFuture<Map<K, V>>) cacheLoader.asyncLoadAll(keysToLoad, executor);
        return loaded;
      } catch (RuntimeException e) {
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CompletionException(e);
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    };
  }

  /** Returns whether the supplied cache loader has bulk load functionality. */
  boolean canBulkLoad(AsyncCacheLoader<?, ?> loader) {
    try {
      Class<?> defaultLoaderClass = AsyncCacheLoader.class;
      if (loader instanceof CacheLoader<?, ?>) {
        defaultLoaderClass = CacheLoader.class;

        Method classLoadAll = loader.getClass().getMethod("loadAll", Set.class);
        Method defaultLoadAll = CacheLoader.class.getMethod("loadAll", Set.class);
        if (!classLoadAll.equals(defaultLoadAll)) {
          return true;
        }
      }

      Method classAsyncLoadAll = loader.getClass().getMethod(
          "asyncLoadAll", Set.class, Executor.class);
      Method defaultAsyncLoadAll = defaultLoaderClass.getMethod(
          "asyncLoadAll", Set.class, Executor.class);
      return !classAsyncLoadAll.equals(defaultAsyncLoadAll);
    } catch (NoSuchMethodException | SecurityException e) {
      logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
      return false;
    }
  }

  @Override
  public CompletableFuture<V> get(K key) {
    return get(key, mappingFunction);
  }

  @Override
  public CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys) {
    if (bulkMappingFunction != null) {
      return getAll(keys, bulkMappingFunction);
    }

    Function<K, CompletableFuture<V>> mappingFunction = this::get;
    var result = new LinkedHashMap<K, CompletableFuture<V>>(calculateHashMapCapacity(keys));
    for (K key : keys) {
      var future = result.computeIfAbsent(key, mappingFunction);
      requireNonNull(future);
    }
    return composeResult(result);
  }

  @Override
  public LoadingCache<K, V> synchronous() {
    return (cacheView == null) ? (cacheView = new LoadingCacheView<>(this)) : cacheView;
  }

  /* --------------- Synchronous views --------------- */

  static final class LoadingCacheView<K, V>
      extends AbstractCacheView<K, V> implements LoadingCache<K, V> {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial")
    final LocalAsyncLoadingCache<K, V> asyncCache;

    LoadingCacheView(LocalAsyncLoadingCache<K, V> asyncCache) {
      this.asyncCache = requireNonNull(asyncCache);
    }

    @Override
    LocalAsyncLoadingCache<K, V> asyncCache() {
      return asyncCache;
    }

    @Override
    @SuppressWarnings("PMD.PreserveStackTrace")
    public V get(K key) {
      return resolve(asyncCache.get(key));
    }

    @Override
    @SuppressWarnings("PMD.PreserveStackTrace")
    public Map<K, V> getAll(Iterable<? extends K> keys) {
      return resolve(asyncCache.getAll(keys));
    }

    @Override
    public CompletableFuture<V> refresh(K key) {
      requireNonNull(key);

      Object keyReference = asyncCache.cache().referenceKey(key);
      for (;;) {
        var future = tryOptimisticRefresh(key, keyReference);
        if (future == null) {
          future = tryComputeRefresh(key, keyReference);
        }
        if (future != null) {
          return future;
        }
      }
    }

    @Override
    public CompletableFuture<Map<K, V>> refreshAll(Iterable<? extends K> keys) {
      var result = new LinkedHashMap<K, CompletableFuture<V>>(calculateHashMapCapacity(keys));
      for (K key : keys) {
        result.computeIfAbsent(key, this::refresh);
      }
      return composeResult(result);
    }

    /** Attempts to avoid a reload if the entry is absent, or a load or reload is in-flight. */
    @SuppressWarnings("FutureReturnValueIgnored")
    private @Nullable CompletableFuture<V> tryOptimisticRefresh(K key, Object keyReference) {
      // If a refresh is in-flight, then return it directly. If completed and not yet removed, then
      // remove to trigger a new reload.
      @SuppressWarnings("unchecked")
      var lastRefresh = (CompletableFuture<V>) asyncCache.cache().refreshes().get(keyReference);
      if (lastRefresh != null) {
        if (Async.isReady(lastRefresh) || asyncCache.cache().isPendingEviction(key)) {
          asyncCache.cache().refreshes().remove(keyReference, lastRefresh);
        } else {
          return lastRefresh;
        }
      }

      // If the entry is absent then perform a new load, else if in-flight then return it
      var oldValueFuture = asyncCache.cache().getIfPresentQuietly(key);
      if ((oldValueFuture == null)
          || (oldValueFuture.isDone() && oldValueFuture.isCompletedExceptionally())) {
        if (oldValueFuture != null) {
          asyncCache.cache().remove(key, oldValueFuture);
        }
        var future = asyncCache.get(key, asyncCache.mappingFunction, /* recordStats */ false);
        @SuppressWarnings("unchecked")
        var prior = (CompletableFuture<V>) asyncCache.cache()
            .refreshes().putIfAbsent(keyReference, future);
        var result = (prior == null) ? future : prior;
        result.whenComplete((r, e) -> asyncCache.cache().refreshes().remove(keyReference, result));
        return result;
      } else if (!oldValueFuture.isDone()) {
        // no-op if load is pending
        return oldValueFuture;
      }

      // Fallback to the slow path, possibly retrying
      return null;
    }

    /** Begins a refresh if the entry has materialized and no reload is in-flight. */
    @SuppressWarnings("FutureReturnValueIgnored")
    private @Nullable CompletableFuture<V> tryComputeRefresh(K key, Object keyReference) {
      long[] startTime = new long[1];
      boolean[] refreshed = new boolean[1];
      @SuppressWarnings({"rawtypes", "unchecked"})
      CompletableFuture<V>[] oldValueFuture = new CompletableFuture[1];
      var future = asyncCache.cache().refreshes().computeIfAbsent(keyReference, k -> {
        oldValueFuture[0] = asyncCache.cache().getIfPresentQuietly(key);
        V oldValue = Async.getIfReady(oldValueFuture[0]);
        if (oldValue == null) {
          return null;
        }

        refreshed[0] = true;
        startTime[0] = asyncCache.cache().statsTicker().read();
        try {
          var reloadFuture = asyncCache.cacheLoader.asyncReload(
              key, oldValue, asyncCache.cache().executor());
          return requireNonNull(reloadFuture, "Null future");
        } catch (RuntimeException e) {
          throw e;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new CompletionException(e);
        } catch (Exception e) {
          throw new CompletionException(e);
        }
      });

      if (future == null) {
        // Retry the optimistic path
        return null;
      }

      @SuppressWarnings("unchecked")
      var castedFuture = (CompletableFuture<V>) future;
      if (refreshed[0]) {
        castedFuture.whenComplete((newValue, error) -> {
          long loadTime = asyncCache.cache().statsTicker().read() - startTime[0];
          if (error != null) {
            if (!(error instanceof CancellationException) && !(error instanceof TimeoutException)) {
              logger.log(Level.WARNING, "Exception thrown during refresh", error);
            }
            asyncCache.cache().refreshes().remove(keyReference, castedFuture);
            asyncCache.cache().statsCounter().recordLoadFailure(loadTime);
            return;
          }

          boolean[] discard = new boolean[1];
          var value = asyncCache.cache().compute(key, (ignored, currentValue) -> {
            var successful = asyncCache.cache().refreshes().remove(keyReference, castedFuture);
            if (successful && (currentValue == oldValueFuture[0])) {
              if (currentValue == null) {
                // If the entry is absent then discard the refresh and maybe notifying the listener
                discard[0] = (newValue != null);
                return null;
              } else if ((currentValue == newValue) || (currentValue == castedFuture)) {
                // If the reloaded value is the same instance then no-op
                return currentValue;
              } else if (newValue == Async.getIfReady((CompletableFuture<?>) currentValue)) {
                // If the completed futures hold the same value instance then no-op
                return currentValue;
              }
              return (newValue == null) ? null : castedFuture;
            }
            // Otherwise, a write invalidated the refresh so discard it and notify the listener
            discard[0] = true;
            return currentValue;
          }, asyncCache.cache().expiry(), /* recordLoad */ false, /* recordLoadFailure */ true);

          if (discard[0] && (newValue != null)) {
            var cause = (value == null) ? RemovalCause.EXPLICIT : RemovalCause.REPLACED;
            asyncCache.cache().notifyRemoval(key, castedFuture, cause);
          }
          if (newValue == null) {
            asyncCache.cache().statsCounter().recordLoadFailure(loadTime);
          } else {
            asyncCache.cache().statsCounter().recordLoadSuccess(loadTime);
          }
        });
      }
      return castedFuture;
    }
  }
}
