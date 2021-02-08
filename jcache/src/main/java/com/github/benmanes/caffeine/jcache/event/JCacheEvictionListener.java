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
package com.github.benmanes.caffeine.jcache.event;

import static java.util.Objects.requireNonNull;

import javax.cache.Cache;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;

/**
 * A listener that provides an adapter to publish events in the order of the actions being performed
 * on a key.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheEvictionListener<K, V> implements RemovalListener<K, Expirable<V>> {
  private final JCacheStatisticsMXBean statistics;
  private final EventDispatcher<K, V> dispatcher;

  private Cache<K, V> cache;

  @SuppressWarnings("NullAway.Init")
  public JCacheEvictionListener(EventDispatcher<K, V> dispatcher,
      JCacheStatisticsMXBean statistics) {
    this.dispatcher = requireNonNull(dispatcher);
    this.statistics = requireNonNull(statistics);
  }

  /**
   * Sets the cache instance that was created with this listener.
   *
   * @param cache the cache that uses this loader
   */
  public void setCache(Cache<K, V> cache) {
    this.cache = requireNonNull(cache);
  }

  @Override
  @SuppressWarnings("NullAway")
  public void onRemoval(K key, @Nullable Expirable<V> expirable, RemovalCause cause) {
    if (expirable != null) {
      V value = expirable.get();
      if (cause == RemovalCause.EXPIRED) {
        dispatcher.publishExpiredQuietly(cache, key, value);
      } else {
        dispatcher.publishRemovedQuietly(cache, key, value);
      }
      statistics.recordEvictions(1L);
    }
  }
}
