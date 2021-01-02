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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

  final boolean canBulkLoad;
  final AsyncCacheLoader<K, V> loader;

  @Nullable LoadingCacheView<K, V> cacheView;

  @SuppressWarnings("unchecked")
  LocalAsyncLoadingCache(AsyncCacheLoader<? super K, V> loader) {
    this.loader = (AsyncCacheLoader<K, V>) loader;
    this.canBulkLoad = canBulkLoad(loader);
  }

  /** Returns whether the supplied cache loader has bulk load functionality. */
  private static boolean canBulkLoad(AsyncCacheLoader<?, ?> loader) {
    try {
      Class<?> defaultLoaderClass = AsyncCacheLoader.class;
      if (loader instanceof CacheLoader<?, ?>) {
        defaultLoaderClass = CacheLoader.class;

        Method classLoadAll = loader.getClass().getMethod("loadAll", Iterable.class);
        Method defaultLoadAll = CacheLoader.class.getMethod("loadAll", Iterable.class);
        if (!classLoadAll.equals(defaultLoadAll)) {
          return true;
        }
      }

      Method classAsyncLoadAll = loader.getClass().getMethod(
          "asyncLoadAll", Iterable.class, Executor.class);
      Method defaultAsyncLoadAll = defaultLoaderClass.getMethod(
          "asyncLoadAll", Iterable.class, Executor.class);
      return !classAsyncLoadAll.equals(defaultAsyncLoadAll);
    } catch (NoSuchMethodException | SecurityException e) {
      logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
      return false;
    }
  }

  @Override
  public CompletableFuture<V> get(K key) {
    return get(key, loader::asyncLoad);
  }

  @Override
  public CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys) {
    if (canBulkLoad) {
      return getAll(keys, loader::asyncLoadAll);
    }

    Map<K, CompletableFuture<V>> result = new LinkedHashMap<>();
    Function<K, CompletableFuture<V>> mappingFunction = this::get;
    for (K key : keys) {
      CompletableFuture<V> future = result.computeIfAbsent(key, mappingFunction);
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
    @SuppressWarnings("FutureReturnValueIgnored")
    public void refresh(K key) {
      requireNonNull(key);

      long[] writeTime = new long[1];
      CompletableFuture<V> oldValueFuture = asyncCache.cache().getIfPresentQuietly(key, writeTime);
      if ((oldValueFuture == null)
          || (oldValueFuture.isDone() && oldValueFuture.isCompletedExceptionally())) {
        asyncCache.get(key, asyncCache.loader::asyncLoad, /* recordStats */ false);
        return;
      } else if (!oldValueFuture.isDone()) {
        // no-op if load is pending
        return;
      }

      oldValueFuture.thenAccept(oldValue -> {
        long now = asyncCache.cache().statsTicker().read();
        CompletableFuture<V> refreshFuture = (oldValue == null)
            ? asyncCache.loader.asyncLoad(key, asyncCache.cache().executor())
            : asyncCache.loader.asyncReload(key, oldValue, asyncCache.cache().executor());
        refreshFuture.whenComplete((newValue, error) -> {
          long loadTime = asyncCache.cache().statsTicker().read() - now;
          if (error != null) {
            asyncCache.cache().statsCounter().recordLoadFailure(loadTime);
            logger.log(Level.WARNING, "Exception thrown during refresh", error);
            return;
          }

          boolean[] discard = new boolean[1];
          asyncCache.cache().compute(key, (k, currentValue) -> {
            if (currentValue == null) {
              return (newValue == null) ? null : refreshFuture;
            } else if (currentValue == oldValueFuture) {
              long expectedWriteTime = writeTime[0];
              if (asyncCache.cache().hasWriteTime()) {
                asyncCache.cache().getIfPresentQuietly(key, writeTime);
              }
              if (writeTime[0] == expectedWriteTime) {
                return (newValue == null) ? null : refreshFuture;
              }
            }
            discard[0] = true;
            return currentValue;
          }, /* recordMiss */ false, /* recordLoad */ false, /* recordLoadFailure */ true);

          if (discard[0] && asyncCache.cache().hasRemovalListener()) {
            asyncCache.cache().notifyRemoval(key, refreshFuture, RemovalCause.REPLACED);
          }
          if (newValue == null) {
            asyncCache.cache().statsCounter().recordLoadFailure(loadTime);
          } else {
            asyncCache.cache().statsCounter().recordLoadSuccess(loadTime);
          }
        });
      });
    }
  }
}
