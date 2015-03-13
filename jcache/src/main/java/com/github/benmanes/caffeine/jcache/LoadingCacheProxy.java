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

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
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

  public LoadingCacheProxy(String name, CacheManager cacheManager,
      CaffeineConfiguration<K, V> configuration, LoadingCache<K, Expirable<V>> cache,
      EventDispatcher<K, V> dispatcher, CacheLoader<K, V> cacheLoader,
      ExpiryPolicy expiry, Ticker ticker, JCacheStatisticsMXBean statistics) {
    super(name, cacheManager, configuration, cache, dispatcher,
        Optional.of(cacheLoader), expiry, ticker, statistics);
    this.cache = cache;
  }

  @Override
  public V get(K key) {
    final long start = ticker.read();
    V value = doSafely(() -> {
      Expirable<V> expirable = cache.getIfPresent(key);
      if ((expirable == null) || expirable.hasExpired(currentTimeMillis())) {
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
        setExpirationTime(expirable, currentTimeMillis(), expiry::getExpiryForAccess);
        return copyValue(expirable);
      }
      return null;
    });
    statistics.recordGetTime(ticker.read() - start);
    return value;
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    final long start = ticker.read();
    Map<K, V> result = doSafely(() -> {
      Map<K, Expirable<V>> entries = getAndFilterExpiredEntries(keys);
      statistics.recordHits(entries.size());
      if (entries.size() != keys.size()) {
        List<K> keysToLoad = keys.stream()
            .filter(key -> !entries.containsKey(key))
            .collect(Collectors.<K>toList());
        statistics.recordMisses(keysToLoad.size());
        entries.putAll(cache.getAll(keysToLoad));
      }
      return copyMap(entries);
    });
    statistics.recordGetTime(ticker.read() - start);
    return result;
  }

  /** Returns all of the mappings present, expiring as required. */
  private Map<K, Expirable<V>> getAndFilterExpiredEntries(Set<? extends K> keys) {
    Map<K, Expirable<V>> result = new HashMap<>(cache.getAllPresent(keys));

    int expired = 0;
    long now = currentTimeMillis();
    for (Iterator<Map.Entry<K, Expirable<V>>> i = result.entrySet().iterator(); i.hasNext();) {
      Map.Entry<K, Expirable<V>> entry = i.next();
      if (entry.getValue().hasExpired(now)) {
        if (cache.asMap().remove(entry.getKey(), entry.getValue())) {
          dispatcher.publishExpired(this, entry.getKey(), entry.getValue().get());
          expired++;
        }
        i.remove();
      }
    }
    statistics.recordEvictions(expired);
    return result;
  }

  @Override
  public void loadAll(Set<? extends K> keys, boolean replaceExistingValues,
      CompletionListener completionListener) {
    requireNotClosed();
    for (K key : keys) {
      requireNonNull(key);
    }

    ForkJoinPool.commonPool().execute(() -> {
      try {
        if (replaceExistingValues) {
          int[] ignored = { 0 };
          Map<K, V> loaded = cacheLoader.get().loadAll(keys);
          for (Map.Entry<? extends K, ? extends V> entry : loaded.entrySet()) {
            putNoCopyOrAwait(entry.getKey(), entry.getValue(), false, ignored);
          }
          completionListener.onCompletion();
        } else {
          getAll(keys);
        }
        completionListener.onCompletion();
      } catch (Exception e) {
        completionListener.onException(e);
      } finally {
        dispatcher.ignoreSynchronous();
      }
    });
  }
}
