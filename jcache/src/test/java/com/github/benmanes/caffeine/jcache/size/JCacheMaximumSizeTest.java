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

import static com.google.common.truth.Truth.assertThat;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryRemovedListener;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases that ensure the maximum size< setting is honored by the cache and removal
 * notifications are published.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheMaximumSizeTest {
  private static final int MAXIMUM = 10;

  private static JCacheFixture jcacheFixture(AtomicInteger removed) {
    return JCacheFixture.builder()
        .configure(config -> {
          config.setMaximumSize(OptionalLong.of(MAXIMUM));
          CacheEntryRemovedListener<Integer, Integer> listener =
              events -> removed.incrementAndGet();
          var listenerConfiguration = new MutableCacheEntryListenerConfiguration<>(
              () -> listener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true);
          config.addCacheEntryListenerConfiguration(listenerConfiguration);
          config.setExecutorFactory(MoreExecutors::directExecutor);
        }).build();
  }

  @Test
  void evict() {
    var removed = new AtomicInteger();
    try (var fixture = jcacheFixture(removed)) {
      for (int i = 0; i < 2 * MAXIMUM; i++) {
        fixture.jcache().put(i, i);
      }
      assertThat(removed.get()).isEqualTo(MAXIMUM);
    }
  }
}
