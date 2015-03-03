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
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CompletionListener;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;

/**
 * An implementation of JSR-107 {@link Cache} backed by a Caffeine loading cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LoadingCacheProxy<K, V> extends CacheProxy<K, V> {
  private final LoadingCache<K, V> cache;

  LoadingCacheProxy(String name, CacheManager cacheManager,
      CaffeineConfiguration<K, V> configuration, LoadingCache<K, V> cache,
      EventDispatcher<K, V> dispatcher, CacheLoader<K, V> cacheLoader) {
    super(name, cacheManager, configuration, cache, dispatcher, Optional.of(cacheLoader));
    this.cache = cache;
  }

  @Override
  public V get(K key) {
    requireNotClosed();
    try {
      return copyOf(cache.get(key));
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    }
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    requireNotClosed();
    try {
      return copyOf(cache.getAll(keys));
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
          cache.putAll(cacheLoader().get().loadAll(keys));
        } else {
          cache.getAll(keys);
        }
        completionListener.onCompletion();
      } catch (Exception e) {
        completionListener.onException(e);
      }
    });
  }
}
