/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getStatistics;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases that ensure the variable expiry policy is configured.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheExpiryTest {
  private static final long ONE_MINUTE = TimeUnit.MINUTES.toNanos(1);

  private static JCacheFixture jcacheFixture(Expiry<Integer, Integer> expiry) {
    when(expiry.expireAfterCreate(anyInt(), anyInt(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterUpdate(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
    when(expiry.expireAfterRead(anyInt(), anyInt(), anyLong(), anyLong())).thenReturn(ONE_MINUTE);
    return JCacheFixture.builder()
        .configure(config -> config.setExpiryFactory(Optional.of(() -> expiry)))
        .build();
  }

  @Test
  void configured() {
    Expiry<Integer, Integer> expiry = Mockito.mock();
    try (var fixture = jcacheFixture(expiry)) {
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
  void nativeDeadline_nearNanosecondSaturation() {
    // The JCache deadline is an absolute millisecond timestamp in the ticker's own arbitrary base.
    // Converting it to nanoseconds first saturates once it passes the horizon, so the subtraction
    // of a ticker that has not yet saturated returns whatever is left below Long.MAX_VALUE: here a
    // one minute deadline would become 100ns. The difference must be taken in milliseconds.
    long now = Long.MAX_VALUE - 100;
    try (var fixture = JCacheFixture.builder()
        .configure(config -> {
          config.setTickerFactory(() -> () -> now);
          config.setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(Duration.ONE_MINUTE));
        }).build();
        var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);

      var expiresAfter = JCacheFixture.getNativeExpiresAfter(cache, KEY_1);
      assertThat(expiresAfter).isEqualTo(java.time.Duration.ofMinutes(1));
    }
  }

  @Test
  void nativeExpiry_systemTicker_publishesExpiredOnce() {
    var expired = new AtomicInteger();
    CacheEntryExpiredListener<Integer, Integer> listener =
        events -> events.forEach(event -> expired.incrementAndGet());
    var listenerConfiguration = new MutableCacheEntryListenerConfiguration<>(
        /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ true);
    try (var fixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(
              new Duration(TimeUnit.MILLISECONDS, 10)));
          config.addCacheEntryListenerConfiguration(listenerConfiguration);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setSchedulerFactory(Scheduler::systemScheduler);
          config.setTickerFactory(Ticker::systemTicker);
          config.setStatisticsEnabled(true);
        }).build();
        var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);

      // On the system ticker the native timer removes the entry, so the event and the eviction
      // count arrive from the scheduled maintenance without a further operation
      JCacheFixture.await().untilAsserted(() -> {
        assertThat(expired.get()).isEqualTo(1);
        assertThat(getStatistics(cache).getCacheEvictions()).isEqualTo(1L);
      });

      cache.unwrap(Cache.class).cleanUp();
      assertThat(cache.containsKey(KEY_1)).isFalse();
      assertThat(expired.get()).isEqualTo(1);
      assertThat(getStatistics(cache).getCacheEvictions()).isEqualTo(1L);
    }
  }
}
