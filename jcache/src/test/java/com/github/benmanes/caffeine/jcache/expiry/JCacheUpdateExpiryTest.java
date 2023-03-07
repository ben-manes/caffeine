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

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.TimeUnit;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.expiry.ModifiedExpiryPolicy;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

/**
 * The test cases that ensure the <tt>expiry for update</tt> time is set for the updated entries.
 * The TCK asserts that the {@link ExpiryPolicy#getExpiryForUpdate()} is only called for the
 * following methods, but does not check that the expiration time was set.
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
@Test(singleThreaded = true)
public final class JCacheUpdateExpiryTest extends AbstractJCacheTest {

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    var configuration = new CaffeineConfiguration<Integer, Integer>();
    configuration.setExpiryPolicyFactory(() -> new ModifiedExpiryPolicy(
        new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
    configuration.setTickerFactory(() -> ticker::read);
    configuration.setStatisticsEnabled(true);
    return configuration;
  }

  @Test
  public void containsKey_expired() {
    jcache.put(KEY_1, VALUE_1);
    ticker.setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));

    assertThat(jcache.containsKey(KEY_1)).isFalse();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  @Test
  public void get_expired() {
    jcache.put(KEY_1, VALUE_1);
    ticker.setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));

    assertThat(jcache.get(KEY_1)).isNull();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  @Test
  public void getAndPut() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    var value = jcache.getAndPut(KEY_1, VALUE_1);
    assertThat(value).isEqualTo(VALUE_1);

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  @Test
  public void getAndReplace() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.getAndReplace(KEY_1, VALUE_2)).isEqualTo(VALUE_1);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  @Test
  public void put() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    jcache.put(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS()
            ).isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  @Test
  public void putAll() {
    jcache.putAll(entries);
    advanceHalfExpiry();

    jcache.putAll(entries);
    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS())
          .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  @Test
  public void replace() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    jcache.replace(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  @Test
  public void replaceConditionally() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.replace(KEY_1, VALUE_1, VALUE_2)).isTrue();
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  @Test
  public void replaceConditionally_failed() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.replace(KEY_1, VALUE_2, VALUE_3)).isFalse();
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS()).isEqualTo(START_TIME.plus(EXPIRY_DURATION).toMillis());
  }

  @Test
  public void invoke() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    var result = jcache.invoke(KEY_1, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    });
    assertThat(result).isNull();

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  @Test
  public void invokeAll() {
    jcache.putAll(entries);
    advanceHalfExpiry();

    var result = jcache.invokeAll(keys, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    });
    assertThat(result).isEmpty();

    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS())
          .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }
}
