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

import javax.cache.Cache;

import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.RemovalNotification;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;

/**
 * A Caffeine listener that publishes eviction events to the JCache listeners.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheRemovalListener<K, V> implements RemovalListener<K, Expirable<V>> {
  private final JCacheStatisticsMXBean statistics;
  private final EventDispatcher<K, V> dispatcher;

  private Cache<K, V> cache;

  public JCacheRemovalListener(EventDispatcher<K, V> dispatcher,
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
  public void onRemoval(RemovalNotification<K, Expirable<V>> notification) {
    if (notification.wasEvicted()) {
      dispatcher.publishRemoved(cache, notification.getKey(), notification.getValue().get());
      dispatcher.ignoreSynchronous();
      statistics.recordEvictions(1L);
    }
  }
}
