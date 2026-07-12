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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.CacheProxy;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.copy.Copier;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.google.errorprone.annotations.Var;

/**
 * An adapter from a JCache cache loader to Caffeine's.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheLoaderAdapter<K, V>
    implements com.github.benmanes.caffeine.cache.CacheLoader<K, @Nullable Expirable<V>> {
  private static final Logger logger = System.getLogger(JCacheLoaderAdapter.class.getName());

  private final JCacheStatisticsMXBean statistics;
  private final EventDispatcher<K, V> dispatcher;
  private final CacheLoader<K, V> delegate;
  private final ClassLoader classLoader;
  private final ExpiryPolicy expiry;
  private final Copier copier;
  private final Ticker ticker;

  private @Nullable CacheProxy<K, V> cache;

  public JCacheLoaderAdapter(CacheLoader<K, V> delegate, EventDispatcher<K, V> dispatcher,
      ExpiryPolicy expiry, Ticker ticker, JCacheStatisticsMXBean statistics,
      Copier copier, ClassLoader classLoader) {
    this.dispatcher = requireNonNull(dispatcher);
    this.statistics = requireNonNull(statistics);
    this.classLoader = requireNonNull(classLoader);
    this.delegate = requireNonNull(delegate);
    this.copier = requireNonNull(copier);
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
  @SuppressWarnings("ConstantValue")
  public @Nullable Expirable<V> load(K key) {
    try {
      boolean statsEnabled = statistics.isEnabled();
      long start = statsEnabled ? ticker.read() : 0L;

      V value = delegate.load(key);
      @Var Expirable<V> expirable = null;
      if (value != null) {
        requireNonNull(cache);
        V copy = copyOf(value);
        long expireTime = expireTimeMillis(/* created= */ true);
        if (expireTime == 0L) {
          // Per JSR-107 1.1.1 p.55: ZERO duration → entry is already expired
          // and will not be added to the Cache. Match the put-path convention
          // of publishing EXPIRED in place of CREATED.
          dispatcher.publishExpired(cache, key, copy);
        } else {
          expirable = new Expirable<>(copy, expireTime);
          dispatcher.publishCreated(cache, key, copy);
        }
      }

      if (statsEnabled) {
        // Subtracts the load time from the get time
        statistics.recordGetTime(start - ticker.read());
      }
      return expirable;
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
      requireNonNull(cache);

      @SuppressWarnings("ConstantValue")
      Map<K, V> loaded = delegate.loadAll(keys);
      var result = new HashMap<K, Expirable<V>>(loaded.size());
      for (var entry : loaded.entrySet()) {
        K key = entry.getKey();
        V value = entry.getValue();
        if ((key == null) || (value == null)) {
          continue;
        }
        V copy = copyOf(value);
        long expireTime = expireTimeMillis(/* created= */ true);
        if (expireTime == 0L) {
          // ZERO → already expired and not added; match the put-path convention.
          dispatcher.publishExpired(cache, key, copy);
        } else {
          result.put(key, new Expirable<>(copy, expireTime));
        }
      }
      for (var entry : result.entrySet()) {
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

  @Override
  public @Nullable Expirable<V> reload(K key, Expirable<V> oldValue) {
    try {
      V value = delegate.load(key);
      if (value == null) {
        return null;
      }
      requireNonNull(cache);
      V copy = copyOf(value);
      @Var long expireTime = expireTimeMillis(/* created= */ false);
      if (expireTime == Long.MIN_VALUE) {
        expireTime = oldValue.getExpireTimeMillis();
      }
      if (expireTime == 0L) {
        dispatcher.publishExpiredQuietly(cache, key, copy);
        return null;
      }
      dispatcher.publishUpdatedQuietly(cache, key, oldValue.get(), copy);
      return new Expirable<>(copy, expireTime);
    } catch (CacheLoaderException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheLoaderException(e);
    }
  }

  /** Returns a copy of the value if value-based caching is enabled. */
  private V copyOf(V value) {
    return requireNonNull(copier.copy(value, classLoader));
  }

  private long expireTimeMillis(boolean created) {
    try {
      Duration duration = created ? expiry.getExpiryForCreation() : expiry.getExpiryForUpdate();
      if (duration == null) {
        return created ? Long.MAX_VALUE : Long.MIN_VALUE;
      } else if (duration.isZero()) {
        return 0;
      } else if (duration.isEternal()) {
        return Long.MAX_VALUE;
      }
      long millis = TimeUnit.NANOSECONDS.toMillis(ticker.read());
      long expireTime = duration.getAdjustedTime(millis);
      return ((expireTime == 0L) || (expireTime == Long.MAX_VALUE)) ? (expireTime - 1) : expireTime;
    } catch (RuntimeException e) {
      // Per JSR-107 1.1.1 p.55 a throwing policy uses an implementation default: creation falls
      // back to eternal so the entry is not lost, an update leaves the expiration unchanged
      logger.log(Level.WARNING, "Exception thrown by expiry policy", e);
      return created ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }
}
