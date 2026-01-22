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
import static com.github.benmanes.caffeine.jcache.JCacheFixture.VALUE_3;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.getExpirable;
import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.TimeUnit;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.expiry.ModifiedExpiryPolicy;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.JCacheFixture;

/**
 * The test cases that ensure the expiry for update time is set for the updated entries. The TCK
 * asserts that the {@link ExpiryPolicy#getExpiryForUpdate()} is only called for the following
 * methods, but does not check that the expiration time was set.
 * <ul>
 *   <li>getAndPut
 *   <li>getAndReplace
 *   <li>put
 *   <li>putAll
 *   <li>replace
 *   <li>invoke
 *   <li>invokeAll
 * </ul>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheUpdateExpiryTest {

  private static JCacheFixture jcacheFixture() {
    return JCacheFixture.builder()
        .configure(config -> {
          config.setExpiryPolicyFactory(() -> new ModifiedExpiryPolicy(
              new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
          config.setStatisticsEnabled(true);
        }).build();
  }

  @Test
  void containsKey_expired() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.ticker().setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));

      assertThat(fixture.jcache().containsKey(KEY_1)).isFalse();
      assertThat(getExpirable(fixture.jcache(), KEY_1)).isNull();
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

  @Test
  void getAndPut() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      var value = fixture.jcache().getAndPut(KEY_1, VALUE_1);
      assertThat(value).isEqualTo(VALUE_1);

      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void getAndReplace() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcache().getAndReplace(KEY_1, VALUE_2)).isEqualTo(VALUE_1);
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void put() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      fixture.jcache().put(KEY_1, VALUE_2);
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void putAll() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().putAll(JCacheFixture.ENTRIES);
      fixture.advanceHalfExpiry();

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
  void replace() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcache().replace(KEY_1, VALUE_2)).isTrue();
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void replaceConditionally() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcache().replace(KEY_1, VALUE_1, VALUE_2)).isTrue();
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void replaceConditionally_failed() {
    try (var fixture = jcacheFixture()) {
      fixture.jcache().put(KEY_1, VALUE_1);
      fixture.advanceHalfExpiry();

      assertThat(fixture.jcache().replace(KEY_1, VALUE_2, VALUE_3)).isFalse();
      Expirable<Integer> expirable = getExpirable(fixture.jcache(), KEY_1);
      assertThat(expirable).isNotNull();
      assertThat(expirable.getExpireTimeMillis())
          .isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void invoke() {
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
          .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  void invokeAll() {
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
            .isEqualTo(fixture.currentTime().plus(EXPIRY_DURATION).toMillis());
      }
    }
  }
}
