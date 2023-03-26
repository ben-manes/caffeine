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
package com.github.benmanes.caffeine.guava;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * A Caffeine-backed loading cache through a Guava facade.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"PMD.ExceptionAsFlowControl", "serial"})
final class CaffeinatedGuavaLoadingCache<K, V>
    extends CaffeinatedGuavaCache<K, V> implements LoadingCache<K, V> {
  private static final ThreadLocal<Boolean> nullBulkLoad =
      ThreadLocal.withInitial(() -> Boolean.FALSE);
  private static final long serialVersionUID = 1L;

  private final com.github.benmanes.caffeine.cache.LoadingCache<K, V> cache;

  CaffeinatedGuavaLoadingCache(com.github.benmanes.caffeine.cache.LoadingCache<K, V> cache) {
    super(cache);
    this.cache = cache;
  }

  @Override
  @SuppressWarnings({"NullAway", "PMD.PreserveStackTrace"})
  public V get(K key) throws ExecutionException {
    requireNonNull(key);
    try {
      return cache.get(key);
    } catch (InvalidCacheLoadException e) {
      throw e;
    } catch (CacheLoaderException e) {
      throw new ExecutionException(e.getCause());
    } catch (RuntimeException e) {
      throw new UncheckedExecutionException(e);
    } catch (Error e) {
      throw new ExecutionError(e);
    }
  }

  @Override
  @SuppressWarnings({"CatchingUnchecked", "NullAway",
    "PMD.AvoidCatchingNPE", "PMD.PreserveStackTrace"})
  public V getUnchecked(K key) {
    try {
      return cache.get(key);
    } catch (NullPointerException | InvalidCacheLoadException e) {
      throw e;
    } catch (CacheLoaderException e) {
      throw new UncheckedExecutionException(e.getCause());
    } catch (Exception e) {
      throw new UncheckedExecutionException(e);
    } catch (Error e) {
      throw new ExecutionError(e);
    }
  }

  @Override
  @SuppressWarnings({"CatchingUnchecked", "PMD.AvoidCatchingNPE", "PMD.PreserveStackTrace"})
  public ImmutableMap<K, V> getAll(Iterable<? extends K> keys) throws ExecutionException {
    try {
      Map<K, V> result = cache.getAll(keys);
      if (nullBulkLoad.get()) {
        nullBulkLoad.set(false);
        throw new InvalidCacheLoadException("null key or value");
      }
      for (K key : keys) {
        if (!result.containsKey(key)) {
          throw new InvalidCacheLoadException("loadAll failed to return a value for " + key);
        }
      }
      return ImmutableMap.copyOf(result);
    } catch (NullPointerException | InvalidCacheLoadException e) {
      throw e;
    } catch (CacheLoaderException e) {
      throw new ExecutionException(e.getCause());
    } catch (Exception e) {
      throw new UncheckedExecutionException(e);
    } catch (Error e) {
      throw new ExecutionError(e);
    }
  }

  @Override
  @SuppressWarnings({"deprecation", "NullAway"})
  public V apply(K key) {
    return cache.get(key);
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored")
  public void refresh(K key) {
    cache.refresh(key);
  }

  abstract static class CaffeinatedLoader<K, V> implements CacheLoader<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    final com.google.common.cache.CacheLoader<K, V> cacheLoader;

    CaffeinatedLoader(com.google.common.cache.CacheLoader<K, V> cacheLoader) {
      this.cacheLoader = requireNonNull(cacheLoader);
    }
    @Override public CompletableFuture<V> asyncReload(K key, V oldValue, Executor executor) {
      var future = new CompletableFuture<V>();
      try {
        ListenableFuture<V> reloader = cacheLoader.reload(key, oldValue);
        if (reloader == null) {
          future.completeExceptionally(new InvalidCacheLoadException("null future"));
        } else {
          Futures.addCallback(reloader, new FutureCompleter<>(future), Runnable::run);
        }
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
      return future;
    }
  }

  static class InternalSingleLoader<K, V> extends CaffeinatedLoader<K, V> {
    private static final long serialVersionUID = 1L;

    InternalSingleLoader(com.google.common.cache.CacheLoader<K, V> cacheLoader) {
      super(cacheLoader);
    }
    @Override public V load(K key) {
      try {
        V value = cacheLoader.load(key);
        if (value == null) {
          throw new InvalidCacheLoadException("null value");
        }
        return value;
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CacheLoaderException(e);
      } catch (Exception e) {
        throw new CacheLoaderException(e);
      }
    }
  }

  static final class InternalBulkLoader<K, V> extends InternalSingleLoader<K, V> {
    private static final long serialVersionUID = 1L;

    InternalBulkLoader(com.google.common.cache.CacheLoader<K, V> cacheLoader) {
      super(cacheLoader);
    }
    @Override public Map<K, V> loadAll(Set<? extends K> keys) {
      try {
        Map<K, V> loaded = cacheLoader.loadAll(keys);
        if (loaded == null) {
          throw new InvalidCacheLoadException("null map");
        }
        Map<K, V> result = new HashMap<>(loaded.size(), /* load factor */ 1.0f);
        loaded.forEach((key, value) -> {
          if ((key == null) || (value == null)) {
            nullBulkLoad.set(true);
          } else {
            result.put(key, value);
          }
        });
        return result;
      } catch (RuntimeException | Error e) {
        throw e;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CacheLoaderException(e);
      } catch (Exception e) {
        throw new CacheLoaderException(e);
      }
    }
  }

  static class ExternalSingleLoader<K, V> extends CaffeinatedLoader<K, V> {
    private static final long serialVersionUID = 1L;

    ExternalSingleLoader(com.google.common.cache.CacheLoader<K, V> cacheLoader) {
      super(cacheLoader);
    }
    @Override public V load(K key) throws Exception {
      V value = cacheLoader.load(key);
      if (value == null) {
        throw new InvalidCacheLoadException("null value");
      }
      return value;
    }
  }

  static final class ExternalBulkLoader<K, V> extends ExternalSingleLoader<K, V> {
    private static final long serialVersionUID = 1L;

    ExternalBulkLoader(com.google.common.cache.CacheLoader<K, V> cacheLoader) {
      super(cacheLoader);
    }
    @Override public Map<K, V> loadAll(Set<? extends K> keys) throws Exception {
      return cacheLoader.loadAll(keys);
    }
  }

  static final class FutureCompleter<V> implements FutureCallback<V> {
    final CompletableFuture<V> future;

    FutureCompleter(CompletableFuture<V> future) {
      this.future = future;
    }
    @Override public void onSuccess(@Nullable V value) {
      if (value == null) {
        future.completeExceptionally(new InvalidCacheLoadException("null value"));
      } else {
        future.complete(value);
      }
    }
    @Override public void onFailure(Throwable t) {
      future.completeExceptionally(t);
    }
  }
}
