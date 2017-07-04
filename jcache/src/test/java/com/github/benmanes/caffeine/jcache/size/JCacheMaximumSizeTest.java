/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.size;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryRemovedListener;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases that ensure the <tt>maximum size</tt> setting is honored by the cache and removal
 * notifications are published.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
public final class JCacheMaximumSizeTest extends AbstractJCacheTest {
  private static final int MAXIMUM = 10;

  private final AtomicInteger removed = new AtomicInteger();

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    CacheEntryRemovedListener<Integer, Integer> listener = events -> removed.incrementAndGet();
    CaffeineConfiguration<Integer, Integer> configuration = new CaffeineConfiguration<>();
    configuration.setMaximumSize(OptionalLong.of(MAXIMUM));
    CacheEntryListenerConfiguration<Integer, Integer> listenerConfiguration =
        new MutableCacheEntryListenerConfiguration<Integer, Integer>(() -> listener,
            /* filterFactory */ null, /* isOldValueRequired */ false, /* isSynchronous */ true);
    configuration.addCacheEntryListenerConfiguration(listenerConfiguration);
    configuration.setExecutorFactory(MoreExecutors::directExecutor);
    return configuration;
  }

  @Test
  public void evict() {
    for (int i = 0; i < 2 * MAXIMUM; i++) {
      jcache.put(i, i);
    }
    assertThat(removed.get(), is(MAXIMUM));
  }
}
