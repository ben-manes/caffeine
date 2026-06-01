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

import static com.github.benmanes.caffeine.jcache.JCacheFixture.EXPIRY_DURATION;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.START_TIME;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getExpirable;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getStatistics;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.cache.Cache;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * The test cases that ensure the <code>expiry for creation</code> time is set for the created
 * entries. The TCK asserts that the {@link ExpiryPolicy#getExpiryForCreation()} is only called
 * for the following methods, but does not check that the expiration time was updated.
 * <ul>
 *   <li>get (loading)
 *   <li>getAndPut
 *   <li>put
 *   <li>putAll
 *   <li>putIfAbsent
 *   <li>invoke
 *   <li>invokeAll
 * </ul>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheCreationExpiryTest {
  private static final javax.cache.expiry.Duration CREATION =
      new javax.cache.expiry.Duration(MILLISECONDS, EXPIRY_DURATION.toMillis());
  private static final javax.cache.expiry.Duration ACCESS =
      new javax.cache.expiry.Duration(MILLISECONDS, EXPIRY_DURATION.multipliedBy(3).toMillis());

  private static JCacheFixture jcacheFixture() {
    return JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(CREATION));
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setStatisticsEnabled(true);
        }).build();
  }

  /**
   * A fixture whose creation expiry is {@link javax.cache.expiry.Duration#ZERO}. Per
   * {@link ExpiryPolicy#getExpiryForCreation()}, a zero duration means the new entry "is considered
   * to be already expired and will not be added to the Cache" — so no entry is stored, no
   * {@code CREATED} event is published, and no put is recorded.
   */
  private static JCacheFixture zeroCreationFixture(AtomicInteger createdEvents) {
    CacheEntryCreatedListener<Integer, Integer> listener =
        events -> events.forEach(event -> createdEvents.incrementAndGet());
    var listenerConfig = new MutableCacheEntryListenerConfiguration<Integer, Integer>(
        /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
        /* isOldValueRequired= */ false, /* isSynchronous= */ true);
    return JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(
              () -> new CreatedExpiryPolicy(javax.cache.expiry.Duration.ZERO));
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setStatisticsEnabled(true);
          config.addCacheEntryListenerConfiguration(listenerConfig);
        }).build();
  }

  /**
   * Every JCache write path must agree under a zero creation expiry: the spec says the entry "will
   * not be added to the Cache", so none may store it, publish a {@code CREATED} event, or record a
   * put. The {@code EntryProcessor} create path historically diverged from the put-family siblings.
   */
  @ParameterizedTest
  @MethodSource("zeroCreationWriteOps")
  void writeOp_absent_zeroCreationExpiry(Consumer<Cache<Integer, Integer>> op) {
    var createdEvents = new AtomicInteger();
    try (var fixture = zeroCreationFixture(createdEvents)) {
      op.accept(fixture.jcache());

      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
      assertThat(fixture.jcache().containsKey(KEY_1)).isFalse();
      assertThat(createdEvents.get()).isEqualTo(0);
      assertThat(getStatistics(fixture.jcache()).getCachePuts()).isEqualTo(0L);
    }
  }

  static Stream<Arguments> zeroCreationWriteOps() {
    return Stream.of(
        arguments(named("put", (Consumer<Cache<Integer, Integer>>) c -> c.put(KEY_1, VALUE_1))),
        arguments(named("putAll",
            (Consumer<Cache<Integer, Integer>>) c -> c.putAll(Map.of(KEY_1, VALUE_1)))),
        arguments(named("putIfAbsent",
            (Consumer<Cache<Integer, Integer>>) c -> c.putIfAbsent(KEY_1, VALUE_1))),
        arguments(named("getAndPut",
            (Consumer<Cache<Integer, Integer>>) c -> c.getAndPut(KEY_1, VALUE_1))),
        arguments(named("invoke", (Consumer<Cache<Integer, Integer>>) c -> c.invoke(KEY_1,
            (entry, args) -> {
              entry.setValue(VALUE_2);
              return nullRef();
            }))));
  }

  /** No live entry results, so putIfAbsent reports false — matching the RI and put()'s no-put rule. */
  @Test
  void putIfAbsent_absent_zeroCreationExpiry_returnsFalse() {
    try (var fixture = zeroCreationFixture(new AtomicInteger())) {
      assertThat(fixture.jcache().putIfAbsent(KEY_1, VALUE_1)).isFalse();
    }
  }

  private static JCacheFixture distinctDurationsFixture() {
    return JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(
              () -> new JCacheExpiryPolicy(CREATION, /* update= */ null, ACCESS));
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setStatisticsEnabled(true);
        }).build();
  }

  /* --------------- containsKey --------------- */

  @Test
  void containsKey_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.ticker().setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));

      assertThat(fixture.jcache().containsKey(KEY_1)).isFalse();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  /* --------------- get --------------- */

  @Test
  void get_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.ticker().setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));

      assertThat(fixture.jcache().get(KEY_1)).isNull();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  /* --------------- get (loading) --------------- */

  @Test
  void get_loading_absent() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(KEY_1);
      Expirable<Integer> expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());

    }
  }

  @Test
  void get_loading_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcacheLoading().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(KEY_1);
      Expirable<Integer> expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void get_loading_expired_lazy() {
    try (var fixture = jcacheFixture()) {
      fixture.cacheManager().enableStatistics(fixture.jcacheLoading().getName(), false);

      fixture.jcacheLoading().put(KEY_1, VALUE_1);
      fixture.ticker().setAutoIncrementStep(
          Duration.ofMillis((long) (EXPIRY_DURATION.toMillis() / 1.5)));

      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(KEY_1);
    }
  }

  @Test
  void get_loading_present() {
    try (var fixture = jcacheFixture()) {
      fixture.jcacheLoading().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(VALUE_1);
      Expirable<Integer> expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void get_loading_absent_usesCreationExpiry() {
    try (var fixture = distinctDurationsFixture()) {
      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(KEY_1);

      Expirable<Integer> expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void get_loading_present_usesAccessExpiry() {
    try (var fixture = distinctDurationsFixture()) {
      fixture.jcacheLoading().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(VALUE_1);

      Expirable<Integer> expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION.multipliedBy(3)).toMillis());
    }
  }

  /* --------------- getAll (loading) --------------- */

  @Test
  void getAll_loading_absent_usesCreationExpiry() {
    try (var fixture = distinctDurationsFixture()) {
      assertThat(fixture.jcacheLoading().getAll(Set.of(KEY_1))).containsExactly(KEY_1, KEY_1);

      Expirable<Integer> expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void getAll_loading_present_usesAccessExpiry() {
    try (var fixture = distinctDurationsFixture()) {
      fixture.jcacheLoading().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcacheLoading().getAll(Set.of(KEY_1))).containsExactly(KEY_1, VALUE_1);

      Expirable<Integer> expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION.multipliedBy(3)).toMillis());
    }
  }

  /* --------------- getAndPut --------------- */

  @Test
  void getAndPut_absent() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().getAndPut(KEY_1, VALUE_1)).isNull();

      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void getAndPut_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      assertThat(fixture.jcache().getAndPut(KEY_1, VALUE_1)).isNull();
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void getAndPut_present() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcache().getAndPut(KEY_1, VALUE_2)).isEqualTo(VALUE_1);
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- put --------------- */

  @Test
  void put_absent() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);

      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

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

  @Test
  void put_present() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      fixture.jcache().put(KEY_1, VALUE_2);
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- putAll --------------- */

  @Test
  void putAll_absent() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().putAll(JCacheFixture.ENTRIES);

      for (Integer key : JCacheFixture.KEYS) {
        Expirable<Integer> expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  @Test
  void putAll_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().putAll(JCacheFixture.ENTRIES);
      fixture.advancePastExpiry();

      fixture.jcache().putAll(JCacheFixture.ENTRIES);
      for (Integer key : JCacheFixture.KEYS) {
        Expirable<Integer> expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  @Test
  void putAll_present() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().putAll(JCacheFixture.ENTRIES);
      fixture.advanceHalfExpiry();

      fixture.jcache().putAll(JCacheFixture.ENTRIES);
      for (Integer key : JCacheFixture.KEYS) {
        Expirable<Integer> expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  /* --------------- putIfAbsent --------------- */

  @Test
  void putIfAbsent_absent() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().putIfAbsent(KEY_1, VALUE_1)).isTrue();

      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

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

  @Test
  void putIfAbsent_expired_lazy() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().putIfAbsent(KEY_1, VALUE_1)).isTrue();

      fixture.ticker().setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));
      assertThat(fixture.jcache().putIfAbsent(KEY_1, VALUE_2)).isTrue();
    }
  }

  @Test
  void putIfAbsent_present() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().putIfAbsent(KEY_1, VALUE_1)).isTrue();
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcache().putIfAbsent(KEY_1, VALUE_2)).isFalse();
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- invoke --------------- */

  @Test
  void invoke_absent() {
    try (var fixture = jcacheFixture()) {
      var result = fixture.jcache().invoke(KEY_1, (entry, args) -> {
        entry.setValue(VALUE_2);
        return nullRef();
      });
      assertThat(result).isNull();

      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void invoke_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advancePastExpiry();

      var result = fixture.jcache().invoke(KEY_1, (entry, args) -> {
        entry.setValue(VALUE_2);
        return nullRef();
      });
      assertThat(result).isNull();

      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void invoke_present() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      var result = fixture.jcache().invoke(KEY_1, (entry, args) -> {
        entry.setValue(VALUE_2);
        return nullRef();
      });
      assertThat(result).isNull();

      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- invokeAll --------------- */

  @Test
  void invokeAll_absent() {
    try (var fixture = jcacheFixture()) {
      var result = fixture.jcache().invokeAll(JCacheFixture.KEYS, (entry, args) -> {
        entry.setValue(VALUE_2);
        return nullRef();
      });
      assertThat(result).isEmpty();

      for (Integer key : JCacheFixture.KEYS) {
        Expirable<Integer> expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  @Test
  void invokeAll_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().putAll(JCacheFixture.ENTRIES);
      fixture.advancePastExpiry();

      var result = fixture.jcache().invokeAll(JCacheFixture.KEYS, (entry, args) -> {
        entry.setValue(VALUE_2);
        return nullRef();
      });
      assertThat(result).isEmpty();

      for (Integer key : JCacheFixture.KEYS) {
        Expirable<Integer> expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  @Test
  void invokeAll_present() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().putAll(JCacheFixture.ENTRIES);
      fixture.advanceHalfExpiry();

      var result = fixture.jcache().invokeAll(JCacheFixture.KEYS, (entry, args) -> {
        entry.setValue(VALUE_2);
        return nullRef();
      });
      assertThat(result).isEmpty();

      for (Integer key : JCacheFixture.KEYS) {
        Expirable<Integer> expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  @Test
  void iterator() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      var expirable1 = requireNonNull(getExpirable(fixture.jcache(), KEY_1));
      fixture.advanceHalfExpiry();

      fixture.jcache().put(JCacheFixture.KEY_2, VALUE_2);
      var expirable2 = requireNonNull(getExpirable(fixture.jcache(), JCacheFixture.KEY_2));
      assertThat(fixture.jcache()).hasSize(2);

      fixture.advanceHalfExpiry();
      assertThat(fixture.jcache()).hasSize(1);
      expirable2.setExpireTimeMillis(expirable1.getExpireTimeMillis());
      assertThat(fixture.jcache()).hasSize(0);
    }
  }
}
