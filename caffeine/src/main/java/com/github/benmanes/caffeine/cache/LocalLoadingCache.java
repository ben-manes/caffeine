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
import static com.github.benmanes.caffeine.cache.LocalAsyncCache.composeResult;
import static java.util.Objects.requireNonNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.Var;

/**
 * This class provides a skeletal implementation of the {@link LoadingCache} interface to minimize
 * the effort required to implement a {@link LocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
interface LocalLoadingCache<K, V> extends LocalManualCache<K, V>, LoadingCache<K, V> {
  Logger logger = System.getLogger(LocalLoadingCache.class.getName());

  /** Returns the {@link AsyncCacheLoader} used by this cache. */
  AsyncCacheLoader<? super K, V> cacheLoader();

  /** Returns the {@link CacheLoader#load} as a mapping function. */
  Function<K, V> mappingFunction();

  /** Returns the {@link CacheLoader#loadAll} as a mapping function, if implemented. */
  @Nullable Function<Set<? extends K>, Map<K, V>> bulkMappingFunction();

  @Override
  @SuppressWarnings("NullAway")
  default V get(K key) {
    return cache().computeIfAbsent(key, mappingFunction());
  }

  @Override
  default Map<K, V> getAll(Iterable<? extends K> keys) {
    Function<Set<? extends K>, Map<K, V>> mappingFunction = bulkMappingFunction();
    return (mappingFunction == null)
        ? loadSequentially(keys)
        : getAll(keys, mappingFunction);
  }

  /** Sequentially loads each missing entry. */
  default Map<K, V> loadSequentially(Iterable<? extends K> keys) {
    var result = new LinkedHashMap<K, V>(calculateHashMapCapacity(keys));
    for (K key : keys) {
      result.put(key, null);
    }

    @Var int count = 0;
    try {
      for (var iter = result.entrySet().iterator(); iter.hasNext();) {
        Map.Entry<K, V> entry = iter.next();
        count++;

        V value = get(entry.getKey());
        if (value == null) {
          iter.remove();
        } else {
          entry.setValue(value);
        }
      }
    } catch (Throwable t) {
      cache().statsCounter().recordMisses(result.size() - count);
      throw t;
    }
    return Collections.unmodifiableMap(result);
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  default CompletableFuture<V> refresh(K key) {
    requireNonNull(key);

    long[] startTime = new long[1];
    @SuppressWarnings({"unchecked", "Varifier"})
    @Nullable V[] oldValue = (V[]) new Object[1];
    @SuppressWarnings({"rawtypes", "unchecked"})
    CompletableFuture<? extends V>[] reloading = new CompletableFuture[1];
    Object keyReference = cache().referenceKey(key);

    var future = cache().refreshes().compute(keyReference, (k, existing) -> {
      if ((existing != null) && !Async.isReady(existing) && !cache().isPendingEviction(key)) {
        return existing;
      }

      try {
        startTime[0] = cache().statsTicker().read();
        oldValue[0] = cache().getIfPresentQuietly(key);
        var refreshFuture = (oldValue[0] == null)
            ? cacheLoader().asyncLoad(key, cache().executor())
            : cacheLoader().asyncReload(key, oldValue[0], cache().executor());
        reloading[0] = requireNonNull(refreshFuture, "Null future");
        return refreshFuture;
      } catch (RuntimeException e) {
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CompletionException(e);
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    });

    if (reloading[0] != null) {
      reloading[0].whenComplete((newValue, error) -> {
        long loadTime = cache().statsTicker().read() - startTime[0];
        if (error != null) {
          if (!(error instanceof CancellationException) && !(error instanceof TimeoutException)) {
            logger.log(Level.WARNING, "Exception thrown during refresh", error);
          }
          cache().refreshes().remove(keyReference, reloading[0]);
          cache().statsCounter().recordLoadFailure(loadTime);
          return;
        }

        boolean[] discard = new boolean[1];
        var value = cache().compute(key, (k, currentValue) -> {
          boolean removed = cache().refreshes().remove(keyReference, reloading[0]);
          if (removed && (currentValue == oldValue[0])) {
            return (currentValue == null) && (newValue == null) ? null : newValue;
          }
          discard[0] = (currentValue != newValue);
          return currentValue;
        }, cache().expiry(), /* recordLoad= */ false, /* recordLoadFailure= */ true);

        if (discard[0] && (newValue != null)) {
          var cause = (value == null) ? RemovalCause.EXPLICIT : RemovalCause.REPLACED;
          cache().notifyRemoval(key, newValue, cause);
        }
        if (newValue == null) {
          cache().statsCounter().recordLoadFailure(loadTime);
        } else {
          cache().statsCounter().recordLoadSuccess(loadTime);
        }
      });
    }

    @SuppressWarnings("unchecked")
    var castedFuture = (CompletableFuture<V>) future;
    return castedFuture;
  }

  @Override
  default CompletableFuture<Map<K, V>> refreshAll(Iterable<? extends K> keys) {
    var result = new LinkedHashMap<K, CompletableFuture<V>>(calculateHashMapCapacity(keys));
    for (K key : keys) {
      result.computeIfAbsent(key, this::refresh);
    }
    return composeResult(result);
  }

  /** Returns a mapping function that adapts to {@link CacheLoader#load}. */
  static <K, V> Function<K, @Nullable V> newMappingFunction(CacheLoader<? super K, V> cacheLoader) {
    return key -> {
      try {
        return cacheLoader.load(key);
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

  /** Returns a mapping function that adapts to {@link CacheLoader#loadAll}, if implemented. */
  static <K, V> @Nullable Function<Set<? extends K>, Map<K, V>> newBulkMappingFunction(
      CacheLoader<? super K, V> cacheLoader) {
    if (!hasLoadAll(cacheLoader)) {
      return null;
    }
    return keysToLoad -> {
      try {
        @SuppressWarnings("unchecked")
        var loaded = (Map<K, V>) cacheLoader.loadAll(keysToLoad);
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
  static boolean hasLoadAll(CacheLoader<?, ?> loader) {
    try {
      Method classLoadAll = loader.getClass().getMethod("loadAll", Set.class);
      Method defaultLoadAll = CacheLoader.class.getMethod("loadAll", Set.class);
      return !classLoadAll.equals(defaultLoadAll);
    } catch (NoSuchMethodException | SecurityException e) {
      logger.log(Level.WARNING, "Cannot determine if CacheLoader can bulk load", e);
      return false;
    }
  }
}
