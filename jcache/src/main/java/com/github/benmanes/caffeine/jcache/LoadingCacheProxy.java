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

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CompletionListener;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;

/**
 * An implementation of JSR-107 {@link Cache} backed by a Caffeine loading cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LoadingCacheProxy<K, V> extends CacheProxy<K, V> {
  private final LoadingCache<K, Expirable<V>> cache;

  LoadingCacheProxy(String name, CacheManager cacheManager,
      CaffeineConfiguration<K, V> configuration, LoadingCache<K, Expirable<V>> cache,
      EventDispatcher<K, V> dispatcher, CacheLoader<K, V> cacheLoader,
      ExpiryPolicy expiry, Ticker ticker) {
    super(name, cacheManager, configuration, cache, dispatcher,
        Optional.of(cacheLoader), expiry, ticker);
    this.cache = cache;
  }

  @Override
  public V get(K key) {
    requireNotClosed();
    try {
      Expirable<V> expirable = cache.getIfPresent(key);
      if ((expirable == null) || expirable.hasExpired(currentTimeMillis())) {
        if (cache.asMap().remove(key, expirable)) {
          dispatcher().publishExpired(this, key, expirable.get());
          statistics().recordEvictions(1);
        }
        expirable = null;
      }

      if (expirable == null) {
        expirable = cache.get(key);
      }
      if (expirable != null) {
        setExpirationTime(expirable, currentTimeMillis(), expiry()::getExpiryForAccess);
        return copyValue(expirable);
      }
      return null;
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    } finally {
      dispatcher().awaitSynchronous();
    }
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    requireNotClosed();
    try {
      Map<K, V> result = copyMap(cache.getAll(keys));
      dispatcher().awaitSynchronous();
      return result;
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    }
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
          Map<K, V> loaded = cacheLoader().get().loadAll(keys);
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
        dispatcher().ignoreSynchronous();
      }
    });
  }
}
