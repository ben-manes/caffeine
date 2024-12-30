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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.Iterator;

import javax.cache.Cache;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryRemovedListener;

import org.mockito.Mockito;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheEvictionListenerTest {

  @Test(dataProvider = "notifications")
  public void publishIfEvicted(Integer key, Expirable<Integer> value, RemovalCause cause) {
    var statistics = new JCacheStatisticsMXBean();
    EvictionListener entryListener = Mockito.mock();
    var dispatcher = new EventDispatcher<Integer, Integer>(Runnable::run);
    var listener = new JCacheEvictionListener<>(dispatcher, statistics);
    dispatcher.register(new MutableCacheEntryListenerConfiguration<>(
        /* listenerFactory= */ () -> entryListener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ false));
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      listener.setCache(cache);
    }
    statistics.enable(true);

    listener.onRemoval(key, value, cause);
    if (cause.wasEvicted()) {
      if (cause == RemovalCause.EXPIRED) {
        verify(entryListener).onExpired(any());
      } else {
        verify(entryListener).onRemoved(any());
      }
      assertThat(statistics.getCacheEvictions()).isEqualTo(1L);
    } else {
      verify(entryListener, never()).onRemoved(any());
      assertThat(statistics.getCacheEvictions()).isEqualTo(0L);
    }
  }

  @DataProvider(name = "notifications")
  public Iterator<Object[]> providesNotifications() {
    return Arrays.stream(RemovalCause.values())
        .filter(RemovalCause::wasEvicted)
        .map(cause -> new Object[] { 1, new Expirable<>(2, 3), cause })
        .iterator();
  }

  interface EvictionListener extends CacheEntryRemovedListener<Integer, Integer>,
      CacheEntryExpiredListener<Integer, Integer> {}
}
