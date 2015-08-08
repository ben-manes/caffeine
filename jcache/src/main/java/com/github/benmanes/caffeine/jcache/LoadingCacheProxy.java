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
import javax.cache.CacheManager;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CompletionListener;

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
public final class LoadingCacheProxy<K, V> extends CacheProxy<K, V> {
  private final LoadingCache<K, Expirable<V>> cache;

  public LoadingCacheProxy(String name, Executor executor, CacheManager cacheManager,
      CaffeineConfiguration<K, V> configuration, LoadingCache<K, Expirable<V>> cache,
      EventDispatcher<K, V> dispatcher, CacheLoader<K, V> cacheLoader,
      ExpiryPolicy expiry, Ticker ticker, JCacheStatisticsMXBean statistics) {
    super(name, executor, cacheManager, configuration, cache, dispatcher,
        Optional.of(cacheLoader), expiry, ticker, statistics);
    this.cache = cache;
  }

  @Override
  public V get(K key) {
    long now = ticker.read();
    long millis = currentTimeMillis();
    V value = doSafely(() -> {
      Expirable<V> expirable = cache.getIfPresent(key);
      if ((expirable != null) && expirable.hasExpired(millis)) {
        if (cache.asMap().remove(key, expirable)) {
          dispatcher.publishExpired(this, key, expirable.get());
          statistics.recordEvictions(1);
        }
        expirable = null;
      }
      if (expirable == null) {
        expirable = cache.get(key);
        statistics.recordMisses(1L);
      } else {
        statistics.recordHits(1L);
      }
      if (expirable != null) {
        setExpirationTime(expirable, millis, expiry::getExpiryForAccess);
        return copyValue(expirable);
      }
      return null;
    });
    statistics.recordGetTime(ticker.read() - now);
    return value;
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    return getAll(keys, true);
  }

  /** Returns the entries, loading if necessary, and optionally updates their access expiry time. */
  private Map<K, V> getAll(Set<? extends K> keys, boolean updateAccessTime) {
    final long now = ticker.read();
    Map<K, V> result = doSafely(() -> {
      Map<K, Expirable<V>> entries = getAndFilterExpiredEntries(keys, updateAccessTime);

      if (entries.size() != keys.size()) {
        List<K> keysToLoad = keys.stream()
            .filter(key -> !entries.containsKey(key))
            .collect(Collectors.<K>toList());
        entries.putAll(cache.getAll(keysToLoad));
      }

      return copyMap(entries);
    });
    statistics.recordGetTime(ticker.read() - now);
    return result;
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
            putNoCopyOrAwait(entry.getKey(), entry.getValue(), false, ignored);
          }
        } else {
          getAll(keys, false);
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
