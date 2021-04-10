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
package com.github.benmanes.caffeine.jcache.integration;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.CacheProxy;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;

/**
 * An adapter from a JCache cache loader to Caffeine's.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheLoaderAdapter<K, V>
    implements com.github.benmanes.caffeine.cache.CacheLoader<K, Expirable<V>> {
  private final JCacheStatisticsMXBean statistics;
  private final EventDispatcher<K, V> dispatcher;
  private final CacheLoader<K, V> delegate;
  private final ExpiryPolicy expiry;
  private final Ticker ticker;

  private CacheProxy<K, V> cache;

  @SuppressWarnings("NullAway.Init")
  public JCacheLoaderAdapter(CacheLoader<K, V> delegate, EventDispatcher<K, V> dispatcher,
      ExpiryPolicy expiry, Ticker ticker, JCacheStatisticsMXBean statistics) {
    this.dispatcher = requireNonNull(dispatcher);
    this.statistics = requireNonNull(statistics);
    this.delegate = requireNonNull(delegate);
    this.expiry = requireNonNull(expiry);
    this.ticker = requireNonNull(ticker);
  }

  /**
   * Sets the cache instance that was created with this loader.
   *
   * @param cache the cache that uses this loader
   */
  public void setCache(CacheProxy<K, V> cache) {
    this.cache = requireNonNull(cache);
  }

  @Override
  public @Nullable Expirable<V> load(K key) {
    try {
      boolean statsEnabled = statistics.isEnabled();
      long start = statsEnabled ? ticker.read() : 0L;

      V value = delegate.load(key);
      if (value == null) {
        return null;
      }

      dispatcher.publishCreated(cache, key, value);

      if (statsEnabled) {
        // Subtracts the load time from the get time
        statistics.recordGetTime(start - ticker.read());
      }
      return new Expirable<>(value, expireTimeMS());
    } catch (CacheLoaderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheLoaderException(e);
    }
  }

  @Override
  public Map<K, Expirable<V>> loadAll(Set<? extends K> keys) {
    try {
      boolean statsEnabled = statistics.isEnabled();
      long start = statsEnabled ? ticker.read() : 0L;

      Map<K, Expirable<V>> result = delegate.loadAll(keys).entrySet().stream()
          .filter(entry -> (entry.getKey() != null) && (entry.getValue() != null))
          .collect(Collectors.toMap(Map.Entry::getKey,
              entry -> new Expirable<>(entry.getValue(), expireTimeMS())));
      for (Map.Entry<K, Expirable<V>> entry : result.entrySet()) {
        dispatcher.publishCreated(cache, entry.getKey(), entry.getValue().get());
      }

      if (statsEnabled) {
        // Subtracts the load time from the get time
        statistics.recordGetTime(start - ticker.read());
      }
      return result;
    } catch (CacheLoaderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheLoaderException(e);
    }
  }

  private long expireTimeMS() {
    try {
      Duration duration = expiry.getExpiryForCreation();
      if (duration.isZero()) {
        return 0;
      } else if (duration.isEternal()) {
        return Long.MAX_VALUE;
      }
      long millis = TimeUnit.NANOSECONDS.toMillis(ticker.read());
      return duration.getAdjustedTime(millis);
    } catch (Exception e) {
      return Long.MAX_VALUE;
    }
  }
}
