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
import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryRemovedListener;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases that ensure the maximum weight setting is honored by the cache and removal
 * notifications are published.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheMaximumWeightTest {
  private static final int MAXIMUM = 10;

  private static JCacheFixture jcacheFixture(AtomicInteger removedWeight) {
    return JCacheFixture.builder()
        .configure(config -> {
          config.setMaximumWeight(OptionalLong.of(MAXIMUM));
          config.setWeigherFactory(Optional.of(() -> (key, value) -> value));
          CacheEntryRemovedListener<Integer, Integer> listener = events -> removedWeight
              .addAndGet(requireNonNull(Iterables.getOnlyElement(events)).getValue());
          var listenerConfiguration = new MutableCacheEntryListenerConfiguration<>(
              () -> listener, /* filterFactory= */ null,
              /* isOldValueRequired= */ true, /* isSynchronous= */ true);
          config.addCacheEntryListenerConfiguration(listenerConfiguration);
          config.setExecutorFactory(MoreExecutors::directExecutor);
        }).build();
  }

  @Test
  void evict() {
    var removedWeight = new AtomicInteger();
    try (var fixture = jcacheFixture(removedWeight)) {
      for (int i = 0; i < MAXIMUM; i++) {
        fixture.jcache().put(i, 1);
      }
      fixture.jcache().put(2 * MAXIMUM, MAXIMUM / 2);
      assertThat(removedWeight.get()).isEqualTo(MAXIMUM / 2);
    }
  }
}
