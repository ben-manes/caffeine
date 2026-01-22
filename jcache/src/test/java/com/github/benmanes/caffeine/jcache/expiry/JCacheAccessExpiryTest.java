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

import static com.github.benmanes.caffeine.jcache.JCacheFixture.ENTRIES;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.EXPIRY_DURATION;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEYS;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.KEY_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_1;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_2;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_3;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getExpirable;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getStatistics;
import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.cache.expiry.AccessedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CompletionListenerFuture;
import javax.cache.processor.EntryProcessorResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

/**
 * The test cases that ensure the <code>expiry for access</code> time is updated for the accessed
 * entries. The TCK asserts that the {@link ExpiryPolicy#getExpiryForAccess()} is only called for
 * the following methods, but does not check that the expiration time was updated.
 * <ul>
 *   <li>get
 *   <li>getAll
 *   <li>invoke
 *   <li>invokeAll
 *   <li>conditional remove (failure)
 *   <li>conditional replace (failure)
 * </ul>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheAccessExpiryTest {

  private static JCacheFixture jcacheFixture() {
    var fixture = JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(() -> new AccessedExpiryPolicy(
              new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
          config.setStatisticsEnabled(true);
        }).build();
    for (int i = 0; i < 100; i++) {
      fixture.jcacheLoading().put(i, -i);
      fixture.jcache().put(i, -i);
    }
    return fixture;
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
  void get_absent() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().get(KEY_1)).isEqualTo(VALUE_1);

      fixture.advancePastExpiry();
      assertThat(fixture.jcache().get(KEY_1)).isNull();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void get_present(boolean eternal) {
    try (var fixture = jcacheFixture()) {
      var expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      if (eternal) {
        expirable.setExpireTimeMillis(Long.MAX_VALUE);
      }

      assertThat(fixture.jcache().get(KEY_1)).isEqualTo(VALUE_1);
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

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
      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(VALUE_1);

      fixture.advancePastExpiry();
      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(KEY_1);

      var expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void get_loading_present(boolean eternal) {
    try (var fixture = jcacheFixture()) {
      var expirable = getExpirable(fixture.jcacheLoading(), KEY_1);
      assertThat(expirable).isNotNull();
      if (eternal) {
        expirable.setExpireTimeMillis(Long.MAX_VALUE);
      }

      assertThat(fixture.jcacheLoading().get(KEY_1)).isEqualTo(VALUE_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- getAllPresent --------------- */

  @Test
  void getAll_absent() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().getAll(KEYS)).isEqualTo(ENTRIES);

      fixture.advancePastExpiry();
      assertThat(fixture.jcache().getAll(KEYS)).isEmpty();

      for (Integer key : KEYS) {
        assertThat(getExpirable(fixture.jcache(), key)).isNull();
      }
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void getAll_present(boolean eternal) {
    try (var fixture = jcacheFixture()) {
      for (Integer key : KEYS) {
        var expirable = getExpirable(fixture.jcacheLoading(), key);
        assertThat(expirable).isNotNull();
        if (eternal) {
          expirable.setExpireTimeMillis(Long.MAX_VALUE);
        }
      }

      assertThat(fixture.jcache().getAll(KEYS)).isEqualTo(ENTRIES);

      for (Integer key : KEYS) {
        var expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  /* --------------- loadAll --------------- */

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void loadAll_present(boolean eternal) throws InterruptedException, ExecutionException {
    try (var fixture = jcacheFixture()) {
      for (Integer key : KEYS) {
        var expirable = getExpirable(fixture.jcacheLoading(), key);
        assertThat(expirable).isNotNull();
        if (eternal) {
          expirable.setExpireTimeMillis(Long.MAX_VALUE);
        }
      }

      var listener = new CompletionListenerFuture();
      fixture.jcacheLoading().loadAll(KEYS, /* replaceExistingValues= */ false, listener);
      listener.get();

      for (Integer key : KEYS) {
        var expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  /* --------------- invoke --------------- */

  @Test
  void invoke_absent() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().get(KEY_1)).isEqualTo(VALUE_1);

      fixture.advancePastExpiry();
      var result = fixture.jcache().invoke(KEY_1, (entry, args) -> entry.getValue());
      assertThat(result).isNull();

      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void invoke_present(boolean eternal) {
    try (var fixture = jcacheFixture()) {
      var expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      if (eternal) {
        expirable.setExpireTimeMillis(Long.MAX_VALUE);
      }

      Integer result = fixture.jcache().invoke(KEY_1, (entry, args) -> entry.getValue());
      assertThat(result).isEqualTo(VALUE_1);
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- invokeAll --------------- */

  @Test
  void invokeAll_absent() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().getAll(KEYS)).isEqualTo(ENTRIES);

      fixture.advancePastExpiry();
      assertThat(fixture.jcache().invokeAll(KEYS, (entry, args) -> entry.getValue())).isEmpty();

      for (Integer key : KEYS) {
        assertThat(getExpirable(fixture.jcache(), key)).isNull();
      }
    }
  }

  @Test
  void invokeAll_present() {
    try (var fixture = jcacheFixture()) {
      var result = fixture.jcache().invokeAll(KEYS, (entry, args) -> entry.getValue());
      var unwrapped = ImmutableMap.copyOf(Maps.transformValues(result, EntryProcessorResult::get));
      assertThat(unwrapped).isEqualTo(ENTRIES);

      for (Integer key : KEYS) {
        var expirable = getExpirable(fixture.jcache(), key);
        assertThat(expirable).isNotNull();
        assertThat(expirable.getExpireTimeMillis())
            .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      }
    }
  }

  /* --------------- conditional remove --------------- */

  @Test
  void removeConditionally_mismatch() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().remove(KEY_1, VALUE_2)).isFalse();

      var expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void removeConditionally_stats() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().getCacheManager().enableStatistics(fixture.jcache().getName(), true);
      fixture.ticker().setAutoIncrementStep(1, TimeUnit.SECONDS);
      assertThat(fixture.jcache().remove(KEY_1, VALUE_1)).isTrue();
      assertThat(getStatistics(fixture.jcache()).getAverageRemoveTime()).isGreaterThan(0);
    }
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void removeConditionally_noStats() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().getCacheManager().enableStatistics(fixture.jcache().getName(), false);
      fixture.ticker().setAutoIncrementStep(1, TimeUnit.SECONDS);
      assertThat(fixture.jcache().remove(KEY_1, VALUE_1)).isTrue();
      assertThat(getStatistics(fixture.jcache()).getAverageRemoveTime()).isEqualTo(0);
    }
  }

  /* --------------- conditional replace --------------- */

  @Test
  void replaceConditionally_mismatch() {
    try (var fixture = jcacheFixture()) {
      assertThat(fixture.jcache().replace(KEY_1, VALUE_2, VALUE_3)).isFalse();

      var expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void replaceConditionally_stats() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().getCacheManager().enableStatistics(fixture.jcache().getName(), true);
      fixture.ticker().setAutoIncrementStep(1, TimeUnit.SECONDS);
      assertThat(fixture.jcache().replace(KEY_1, VALUE_1, VALUE_2)).isTrue();
      assertThat(getStatistics(fixture.jcache()).getAveragePutTime()).isGreaterThan(0);
    }
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void replaceConditionally_noStats() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().getCacheManager().enableStatistics(fixture.jcache().getName(), false);
      fixture.ticker().setAutoIncrementStep(1, TimeUnit.SECONDS);
      assertThat(fixture.jcache().replace(KEY_1, VALUE_1, VALUE_2)).isTrue();
      assertThat(getStatistics(fixture.jcache()).getAveragePutTime()).isEqualTo(0);
    }
  }
}
