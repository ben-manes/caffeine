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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * This class provides a skeletal implementation of the {@link AsyncLoadingCache} interface to
 * minimize the effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
abstract class LocalAsyncLoadingCache<K, V>
    implements LocalAsyncCache<K, V>, AsyncLoadingCache<K, V> {
  static final Logger logger = Logger.getLogger(LocalAsyncLoadingCache.class.getName());

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
      return getAllBulk(keys);
    }

    Map<K, CompletableFuture<V>> result = new LinkedHashMap<>();
    Function<K, CompletableFuture<V>> mappingFunction = this::get;
    for (K key : keys) {
      CompletableFuture<V> future = result.computeIfAbsent(key, mappingFunction);
      requireNonNull(future);
    }
    return composeResult(result);
  }

  /** Computes all of the missing entries in a single {@link CacheLoader#asyncLoadAll} call. */
  @SuppressWarnings("FutureReturnValueIgnored")
  private CompletableFuture<Map<K, V>> getAllBulk(Iterable<? extends K> keys) {
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

    AsyncBulkCompleter completer = new AsyncBulkCompleter(proxies);
    try {
      loader.asyncLoadAll(proxies.keySet(), cache().executor()).whenComplete(completer);
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
  private CompletableFuture<Map<K, V>> composeResult(Map<K, CompletableFuture<V>> futures) {
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
  public LoadingCache<K, V> synchronous() {
    return (cacheView == null) ? (cacheView = new LoadingCacheView<>(this)) : cacheView;
  }

  /** A function executed asynchronously after a bulk load completes. */
  private final class AsyncBulkCompleter implements BiConsumer<Map<K, V>, Throwable> {
    private final Map<K, CompletableFuture<V>> proxies;
    private final long startTime;

    AsyncBulkCompleter(Map<K, CompletableFuture<V>> proxies) {
      this.startTime = cache().statsTicker().read();
      this.proxies = proxies;
    }

    @Override
    public void accept(@Nullable Map<K, V> result, @Nullable Throwable error) {
      long loadTime = cache().statsTicker().read() - startTime;

      if (result == null) {
        if (error == null) {
          error = new CompletionException("null map", null);
        }
        for (Entry<K, CompletableFuture<V>> entry : proxies.entrySet()) {
          cache().remove(entry.getKey(), entry.getValue());
          entry.getValue().obtrudeException(error);
        }
        cache().statsCounter().recordLoadFailure(loadTime);
        logger.log(Level.WARNING, "Exception thrown during asynchronous load", error);
      } else {
        fillProxies(result);
        addNewEntries(result);
        cache().statsCounter().recordLoadSuccess(loadTime);
      }
    }

    /** Populates the proxies with the computed result. */
    private void fillProxies(Map<K, V> result) {
      proxies.forEach((key, future) -> {
        V value = result.get(key);
        future.obtrudeValue(value);
        if (value == null) {
          cache().remove(key, future);
        } else {
          // update the weight and expiration timestamps
          cache().replace(key, future, future);
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
          cache().put(key, CompletableFuture.completedFuture(value));
        }
      });
    }
  }

  /* ---------------- Synchronous views -------------- */

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
      try {
        return asyncCache.get(key).get();
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
    @SuppressWarnings("PMD.PreserveStackTrace")
    public Map<K, V> getAll(Iterable<? extends K> keys) {
      try {
        return asyncCache.getAll(keys).get();
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
          }, /* recordMiss */ false, /* recordLoad */ false);

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
