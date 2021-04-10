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
package com.github.benmanes.caffeine.jcache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CompletionListener;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;

/**
 * An implementation of JSR-107 {@link Cache} backed by a Caffeine loading cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("OvershadowingSubclassFields")
public final class LoadingCacheProxy<K, V> extends CacheProxy<K, V> {
  final LoadingCache<K, Expirable<V>> cache;

  @SuppressWarnings("PMD.ExcessiveParameterList")
  public LoadingCacheProxy(String name, Executor executor, CacheManager cacheManager,
      CaffeineConfiguration<K, V> configuration, LoadingCache<K, Expirable<V>> cache,
      EventDispatcher<K, V> dispatcher, CacheLoader<K, V> cacheLoader,
      ExpiryPolicy expiry, Ticker ticker, JCacheStatisticsMXBean statistics) {
    super(name, executor, cacheManager, configuration, cache, dispatcher,
        Optional.of(cacheLoader), expiry, ticker, statistics);
    this.cache = cache;
  }

  @Override
  @SuppressWarnings("PMD.AvoidCatchingNPE")
  public @Nullable V get(K key) {
    requireNotClosed();
    try {
      return getOrLoad(key);
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    } finally {
      dispatcher.awaitSynchronous();
    }
  }

  /** Retrieves the value from the cache, loading it if necessary. */
  @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
  private @Nullable V getOrLoad(K key) {
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    long millis = 0L;
    Expirable<V> expirable = cache.getIfPresent(key);
    if ((expirable != null) && !expirable.isEternal()) {
      millis = nanosToMillis((start == 0L) ? ticker.read() : start);
      if (expirable.hasExpired(millis)) {
        Expirable<V> expired = expirable;
        cache.asMap().computeIfPresent(key, (k, e) -> {
          if (e == expired) {
            dispatcher.publishExpired(this, key, expired.get());
            statistics.recordEvictions(1);
            return null;
          }
          return e;
        });
        expirable = null;
      }
    }

    if (expirable == null) {
      expirable = cache.get(key);
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
    }

    V value = null;
    if (expirable != null) {
      setAccessExpirationTime(key, expirable, millis);
      value = copyValue(expirable);
    }
    if (statsEnabled) {
      statistics.recordGetTime(ticker.read() - start);
    }
    return value;
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    return getAll(keys, true);
  }

  /** Returns the entries, loading if necessary, and optionally updates their access expiry time. */
  @SuppressWarnings("PMD.AvoidCatchingNPE")
  private Map<K, V> getAll(Set<? extends K> keys, boolean updateAccessTime) {
    requireNotClosed();
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;
    try {
      Map<K, Expirable<V>> entries = getAndFilterExpiredEntries(keys, updateAccessTime);

      if (entries.size() != keys.size()) {
        List<K> keysToLoad = keys.stream()
            .filter(key -> !entries.containsKey(key))
            .collect(Collectors.<K>toList());
        entries.putAll(cache.getAll(keysToLoad));
      }

      Map<K, V> result = copyMap(entries);
      if (statsEnabled) {
        statistics.recordGetTime(ticker.read() - start);
      }
      return result;
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    } finally {
      dispatcher.awaitSynchronous();
    }
  }

  @Override
  public void loadAll(Set<? extends K> keys, boolean replaceExistingValues,
      CompletionListener completionListener) {
    requireNotClosed();
    keys.forEach(Objects::requireNonNull);
    CompletionListener listener = (completionListener == null)
        ? NullCompletionListener.INSTANCE
        : completionListener;

    executor.execute(() -> {
      try {
        if (replaceExistingValues) {
          int[] ignored = { 0 };
          Map<K, V> loaded = cacheLoader.get().loadAll(keys);
          for (Map.Entry<? extends K, ? extends V> entry : loaded.entrySet()) {
            putNoCopyOrAwait(entry.getKey(), entry.getValue(),
                /* publishToWriter */ false, ignored);
          }
        } else {
          getAll(keys, /* updateAccessTime */ false);
        }
        listener.onCompletion();
      } catch (Exception e) {
        listener.onException(e);
      } finally {
        dispatcher.ignoreSynchronous();
      }
    });
  }
}
