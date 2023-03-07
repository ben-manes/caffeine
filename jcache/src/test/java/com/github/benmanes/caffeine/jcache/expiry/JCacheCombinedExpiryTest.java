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

import static com.google.common.truth.Truth.assertThat;

import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

/**
 * The test cases that ensure the native fixed policy can be combined with a {@link ExpiryPolicy}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
public final class JCacheCombinedExpiryTest extends AbstractJCacheTest {

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    var configuration = new CaffeineConfiguration<Integer, Integer>();
    configuration.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(
        new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
    configuration.setExpireAfterWrite(OptionalLong.of(Long.MAX_VALUE));
    configuration.setTickerFactory(() -> ticker::read);
    configuration.setStatisticsEnabled(true);
    return configuration;
  }

  /* --------------- containsKey --------------- */

  @Test
  public void containsKey_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.containsKey(KEY_1)).isFalse();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  /* --------------- get --------------- */

  @Test
  public void get_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.get(KEY_1)).isNull();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  @Test
  public void get_expired_loading() {
    jcacheLoading.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcacheLoading.get(KEY_1)).isEqualTo(KEY_1);
  }

  /* --------------- getAll --------------- */

  @Test
  public void getAll_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.getAll(Set.of(KEY_1))).isEmpty();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  /* --------------- put --------------- */

  @Test
  public void put_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    jcache.put(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  /* --------------- putIfAbsent --------------- */

  @Test
  public void putIfAbsent_expired() {
    jcache.putIfAbsent(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.putIfAbsent(KEY_1, VALUE_2)).isTrue();
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.get()).isEqualTo(VALUE_2);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  /* --------------- remove --------------- */

  @Test
  public void remove_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.remove(KEY_1)).isFalse();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  @Test
  public void removeConditionally_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.remove(KEY_1, VALUE_1)).isFalse();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  /* --------------- replace --------------- */

  @Test
  public void replac_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.replace(KEY_1, VALUE_2)).isFalse();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  @Test
  public void replaceConditionally_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.replace(KEY_1, VALUE_1, VALUE_2)).isFalse();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  /* --------------- invoke --------------- */

  @Test
  public void invoke_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    var result = jcache.invoke(KEY_1, (entry, args) -> {
      return null;
    });
    assertThat(result).isNull();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }
}
