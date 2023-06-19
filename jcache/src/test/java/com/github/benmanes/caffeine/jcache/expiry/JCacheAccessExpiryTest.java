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

import javax.cache.expiry.AccessedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.processor.EntryProcessorResult;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

/**
 * The test cases that ensure the <tt>expiry for access</tt> time is updated for the accessed
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
@Test(singleThreaded = true)
public final class JCacheAccessExpiryTest extends AbstractJCacheTest {

  @BeforeMethod
  public void setup() {
    for (int i = 0; i < 100; i++) {
      jcacheLoading.put(i, -i);
      jcache.put(i, -i);
    }
  }

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    var configuration = new CaffeineConfiguration<Integer, Integer>();
    configuration.setExpiryPolicyFactory(() -> new AccessedExpiryPolicy(
        new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION.toMillis())));
    configuration.setTickerFactory(() -> ticker::read);
    configuration.setStatisticsEnabled(true);
    return configuration;
  }

  @DataProvider(name = "eternal")
  public Object[] providesEternal() {
    return new Object[] { true, false };
  }

  /* --------------- containsKey --------------- */

  @Test
  public void containsKey_expired() {
    jcache.put(KEY_1, VALUE_1);
    ticker.setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));

    assertThat(jcache.containsKey(KEY_1)).isFalse();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  /* --------------- get --------------- */

  @Test
  public void get_absent() {
    assertThat(jcache.get(KEY_1)).isEqualTo(VALUE_1);

    advancePastExpiry();
    assertThat(jcache.get(KEY_1)).isNull();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  @Test(dataProvider = "eternal")
  public void get_present(boolean eternal) {
    var expirable = getExpirable(jcache, KEY_1);
    if (eternal) {
      expirable.setExpireTimeMS(Long.MAX_VALUE);
    }

    assertThat(jcache.get(KEY_1)).isEqualTo(VALUE_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  @Test
  public void get_expired() {
    jcache.put(KEY_1, VALUE_1);
    ticker.setAutoIncrementStep(EXPIRY_DURATION.dividedBy(2));

    assertThat(jcache.get(KEY_1)).isNull();
    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  /* --------------- get (loading) --------------- */

  @Test
  public void get_loading_absent() {
    assertThat(jcacheLoading.get(KEY_1)).isEqualTo(VALUE_1);

    advancePastExpiry();
    assertThat(jcacheLoading.get(KEY_1)).isEqualTo(KEY_1);

    var expirable = getExpirable(jcacheLoading, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  @Test(dataProvider = "eternal")
  public void get_loading_present(boolean eternal) {
    var expirable = getExpirable(jcacheLoading, KEY_1);
    if (eternal) {
      expirable.setExpireTimeMS(Long.MAX_VALUE);
    }

    assertThat(jcacheLoading.get(KEY_1)).isEqualTo(VALUE_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  /* --------------- getAllPresent --------------- */

  @Test
  public void getAll_absent() {
    assertThat(jcache.getAll(keys)).isEqualTo(entries);

    advancePastExpiry();
    assertThat(jcache.getAll(keys)).isEmpty();

    for (Integer key : keys) {
      assertThat(getExpirable(jcache, key)).isNull();
    }
  }

  @Test(dataProvider = "eternal")
  public void getAll_present(boolean eternal) {
    for (Integer key : keys) {
      var expirable = getExpirable(jcacheLoading, key);
      if (eternal) {
        expirable.setExpireTimeMS(Long.MAX_VALUE);
      }
    }

    assertThat(jcache.getAll(keys)).isEqualTo(entries);

    for (Integer key : keys) {
      var expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS())
          .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- invoke --------------- */

  @Test
  public void invoke_absent() {
    assertThat(jcache.get(KEY_1)).isEqualTo(VALUE_1);

    advancePastExpiry();
    var result = jcache.invoke(KEY_1, (entry, args) -> entry.getValue());
    assertThat(result).isNull();

    assertThat(getExpirable(jcache, KEY_1)).isNull();
  }

  @Test(dataProvider = "eternal")
  public void invoke_present(boolean eternal) {
    var expirable = getExpirable(jcache, KEY_1);
    if (eternal) {
      expirable.setExpireTimeMS(Long.MAX_VALUE);
    }

    Integer result = jcache.invoke(KEY_1, (entry, args) -> entry.getValue());
    assertThat(result).isEqualTo(VALUE_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  /* --------------- invokeAll --------------- */

  @Test
  public void invokeAll_absent() {
    assertThat(jcache.getAll(keys)).isEqualTo(entries);

    advancePastExpiry();
    assertThat(jcache.invokeAll(keys, (entry, args) -> entry.getValue())).isEmpty();

    for (Integer key : keys) {
      assertThat(getExpirable(jcache, key)).isNull();
    }
  }

  @Test
  public void invokeAll_present() {
    var result = jcache.invokeAll(keys, (entry, args) -> entry.getValue());
    var unwrapped = ImmutableMap.copyOf(Maps.transformValues(result, EntryProcessorResult::get));
    assertThat(unwrapped).isEqualTo(entries);

    for (Integer key : keys) {
      var expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS())
          .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
    }
  }

  /* --------------- conditional remove --------------- */

  @Test
  public void removeConditionally() {
    assertThat(jcache.remove(KEY_1, VALUE_2)).isFalse();

    var expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }

  /* --------------- conditional replace --------------- */

  @Test
  public void replaceConditionally() {
    assertThat(jcache.replace(KEY_1, VALUE_2, VALUE_3)).isFalse();

    var expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS())
        .isEqualTo(currentTime().plus(EXPIRY_DURATION).toMillis());
  }
}
