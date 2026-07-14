/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getExpirable;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.EventType;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.processor.EntryProcessorException;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases that ensure the native fixed policy can be combined with a {@link ExpiryPolicy}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheCombinedExpiryTest {

  private static JCacheFixture jcacheFixture() {
    return JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(
              new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
          config.setExpireAfterWrite(OptionalLong.of(Long.MAX_VALUE));
          config.setStatisticsEnabled(true);
        }).build();
  }

  /* --------------- containsKey --------------- */

  @Test
  void containsKey_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThat(fixture.jcache().containsKey(KEY_1)).isFalse();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  /* --------------- get --------------- */

  @Test
  void get_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThat(fixture.jcache().get(KEY_1)).isNull();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  @Test
  void get_expired_loading() {
    try (var fixture = jcacheFixture()) {
      fixture.jcacheLoading().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(KEY_1);
    }
  }

  /* --------------- getAll --------------- */

  @Test
  void getAll_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThat(fixture.jcache().getAll(Set.of(KEY_1))).isEmpty();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  /* --------------- put --------------- */

  @Test
  void put_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      fixture.jcache().put(KEY_1, VALUE_2);
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());

    }
  }

  /* --------------- putIfAbsent --------------- */

  @Test
  void putIfAbsent_expired() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().putIfAbsent(KEY_1, VALUE_1)).isTrue();
      fixture.advancePastExpiry();

      assertThat(fixture.jcache().putIfAbsent(KEY_1, VALUE_2)).isTrue();
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.get()).isEqualTo(VALUE_2);
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- remove --------------- */

  @Test
  void remove_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long removalsBefore = stats.getCacheRemovals();
      long evictionsBefore = stats.getCacheEvictions();
      assertThat(fixture.jcache().remove(KEY_1)).isFalse();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
      assertThat(stats.getCacheRemovals()).isEqualTo(removalsBefore);
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore + 1);
    }
  }

  @Test
  void removeConditionally_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long removalsBefore = stats.getCacheRemovals();
      long evictionsBefore = stats.getCacheEvictions();
      assertThat(fixture.jcache().remove(KEY_1, VALUE_1)).isFalse();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
      assertThat(stats.getCacheRemovals()).isEqualTo(removalsBefore);
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore + 1);
    }
  }

  /* --------------- replace --------------- */

  @Test
  void replace_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThat(fixture.jcache().replace(KEY_1, VALUE_2)).isFalse();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  @Test
  void replaceConditionally_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThat(fixture.jcache().replace(KEY_1, VALUE_1, VALUE_2)).isFalse();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  /* --------------- invoke --------------- */

  @Test
  void invoke_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      var result = fixture.jcache().invoke(KEY_1, (entry, args) -> nullRef());
      assertThat(result).isNull();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  @Test
  void invoke_expired_processorReplaces_publishesExpiredAndCreates() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long evictionsBefore = stats.getCacheEvictions();
      long putsBefore = stats.getCachePuts();
      var result = fixture.jcache().invoke(KEY_1, (entry, args) -> {
        entry.setValue(VALUE_2);
        return entry.getValue();
      });

      assertThat(result).isEqualTo(VALUE_2);
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.get()).isEqualTo(VALUE_2);
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore + 1);
      assertThat(stats.getCachePuts()).isEqualTo(putsBefore + 1);
    }
  }

  @Test
  void invoke_expired_processorThrows_commitsExpiration() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long evictionsBefore = stats.getCacheEvictions();
      var thrown = assertThrows(EntryProcessorException.class, () ->
          fixture.jcache().invoke(KEY_1, (entry, args) -> {
            entry.setValue(VALUE_2);
            throw new IllegalStateException("boom");
          }));

      assertThat(thrown).hasCauseThat().isInstanceOf(IllegalStateException.class);
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore + 1);
    }
  }

  @Test
  void invoke_live_processorThrows_leavesEntry() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long evictionsBefore = stats.getCacheEvictions();
      assertThrows(EntryProcessorException.class, () ->
          fixture.jcache().invoke(KEY_1, (entry, args) -> {
            entry.setValue(VALUE_2);
            throw new IllegalStateException("boom");
          }));

      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.get()).isEqualTo(VALUE_1);
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore);
    }
  }

  @Test
  void invoke_expired_processorRemoves_publishesExpiredNotRemoved() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long evictionsBefore = stats.getCacheEvictions();
      long removalsBefore = stats.getCacheRemovals();
      var result = fixture.jcache().invoke(KEY_1, (entry, args) -> {
        entry.remove();
        return nullRef();
      });

      assertThat(result).isNull();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore + 1);
      assertThat(stats.getCacheRemovals()).isEqualTo(removalsBefore);
    }
  }

  @Test
  void invoke_expired_processorReplaces_publishesExpiredBeforeCreated() {
    var listener = new RecordingListener();
    var jcacheFixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(
              new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
          config.setExpireAfterWrite(OptionalLong.of(Long.MAX_VALUE));
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        });
    try (var fixture = jcacheFixture.build();
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();
      listener.events.clear();

      var result = cache.invoke(KEY_1, (entry, args) -> {
        entry.setValue(VALUE_2);
        return nullRef();
      });

      assertThat(result).isNull();
      assertThat(listener.events).containsExactly(EventType.EXPIRED, EventType.CREATED).inOrder();
    }
  }

  @Test
  void invoke_expired_processorThrowsError_commitsExpiration() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      var stats = JCacheFixture.getStatistics(fixture.jcache());
      long evictionsBefore = stats.getCacheEvictions();
      // an Error (not a RuntimeException) still commits the already-published expiration
      assertThrows(AssertionError.class, () ->
          fixture.jcache().invoke(KEY_1, (entry, args) -> {
            entry.setValue(VALUE_2);
            throw new AssertionError("boom");
          }));

      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
      assertThat(stats.getCacheEvictions()).isEqualTo(evictionsBefore + 1);
    }
  }

  @Test
  void invoke_expired_processorThrows_awaitsSynchronousExpired() {
    var expiredCount = new AtomicInteger();
    CacheEntryExpiredListener<Integer, Integer> listener =
        events -> events.forEach(event -> expiredCount.incrementAndGet());
    var jcacheFixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(
              new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
          config.setExpireAfterWrite(OptionalLong.of(Long.MAX_VALUE));
          // the default executor is async, so awaitSynchronous is load-bearing (unlike directExecutor)
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        });
    try (var fixture = jcacheFixture.build();
         var cache = fixture.jcache()) {
      cache.put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThrows(EntryProcessorException.class, () ->
          cache.invoke(KEY_1, (entry, args) -> {
            entry.setValue(VALUE_2);
            throw new IllegalStateException("boom");
          }));

      // the committed EXPIRED's synchronous listener is awaited before invoke rethrows the failure
      assertThat(expiredCount.get()).isEqualTo(1);
    }
  }

  private static final class RecordingListener implements
      CacheEntryCreatedListener<Integer, Integer>, CacheEntryExpiredListener<Integer, Integer> {
    final List<EventType> events = new ArrayList<>();

    @Override public void onCreated(
        Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> evts) {
      evts.forEach(event -> events.add(event.getEventType()));
    }
    @Override public void onExpired(
        Iterable<CacheEntryEvent<? extends Integer, ? extends Integer>> evts) {
      evts.forEach(event -> events.add(event.getEventType()));
    }
  }
}
