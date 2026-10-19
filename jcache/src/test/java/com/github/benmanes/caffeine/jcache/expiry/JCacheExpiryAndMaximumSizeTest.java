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
package com.github.benmanes.caffeine.jcache.expiry;

import static com.github.benmanes.caffeine.jcache.JCacheFixture.EXPIRY_DURATION;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases that ensures the variable expiry policy and maximum size settings are set
 * simultaneously.
 *
 * @author github.com/kdombeck (Ken Dombeck)
 */
final class JCacheExpiryAndMaximumSizeTest {
  private static final long ONE_MINUTE = TimeUnit.MINUTES.toNanos(1);
  private static final int MAXIMUM = 10;

  private static JCacheFixture jcacheFixture(
      Expiry<Integer, Integer> expiry, AtomicInteger removed) {
    when(expiry.expireAfterCreate(anyInt(), anyInt(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
    return JCacheFixture.builder()
        .configure(config -> {
          config.setMaximumSize(OptionalLong.of(MAXIMUM));
          CacheEntryRemovedListener<Integer, Integer> listener =
              events -> removed.incrementAndGet();
          var listenerConfiguration = new MutableCacheEntryListenerConfiguration<>(() -> listener,
              /* filterFactory= */ null, /* isOldValueRequired= */ false,
              /* isSynchronous= */ true);
          config.addCacheEntryListenerConfiguration(listenerConfiguration);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setExpiryFactory(Optional.of(() -> expiry));
        }).build();
  }

  @Test
  void expiry() {
    Expiry<Integer, Integer> expiry = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, new AtomicInteger())) {
      fixture.jcache().put(KEY_1, VALUE_1);
      verify(expiry).expireAfterCreate(anyInt(), anyInt(), anyLong());

      fixture.jcache().put(KEY_1, VALUE_2);
      verify(expiry).expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong());

      var value = fixture.jcache().get(KEY_1);
      assertThat(value).isEqualTo(VALUE_2);
      verify(expiry).expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong());
    }
  }

  @Test
  void size() {
    var removed = new AtomicInteger();
    try (var fixture = jcacheFixture(Mockito.mock(), removed)) {
      for (int i = 0; i < 2 * MAXIMUM; i++) {
        fixture.jcache().put(i, i);
      }
      assertThat(removed.get()).isEqualTo(MAXIMUM);
    }
  }

  @Test
  void size_expired() {
    // The vendor expiration outlives the JCache deadline, so core evicts the expired entry for size
    var removed = new ArrayList<Integer>();
    var expired = new ArrayList<Integer>();
    try (var fixture = JCacheFixture.builder()
        .configure(config -> {
          CacheEntryRemovedListener<Integer, Integer> removedListener =
              events -> events.forEach(event -> removed.add(event.getKey()));
          CacheEntryExpiredListener<Integer, Integer> expiredListener =
              events -> events.forEach(event -> expired.add(event.getKey()));
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              () -> removedListener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              () -> expiredListener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
          config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(
              new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
          config.setExpireAfterWrite(OptionalLong.of(EXPIRY_DURATION.multipliedBy(10).toNanos()));
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setMaximumSize(OptionalLong.of(MAXIMUM));
        }).build()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();
      fixture.jcache().put(KEY_2, VALUE_2);

      Cache<?, ?> cache = fixture.jcache().unwrap(Cache.class);
      cache.policy().eviction().orElseThrow().setMaximum(0);
      assertThat(expired).containsExactly(KEY_1);
      assertThat(removed).containsExactly(KEY_2);
    }
  }
}
