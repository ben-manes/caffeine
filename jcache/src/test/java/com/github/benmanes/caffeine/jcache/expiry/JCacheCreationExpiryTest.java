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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.concurrent.TimeUnit;

import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

/**
 * The test cases that ensure the <tt>expiry for creation</tt> time is set for the created entries.
 * The TCK asserts that the {@link ExpiryPolicy#getExpiryForCreation()} is only called for
 * the following methods, but does not check that the expiration time was updated.
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
@Test(singleThreaded = true)
public final class JCacheCreationExpiryTest extends AbstractJCacheTest {

  @Override
  protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    CaffeineConfiguration<Integer, Integer> configuration = new CaffeineConfiguration<>();
    configuration.setExpiryPolicyFactory(() -> new CreatedExpiryPolicy(
        new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION)));
    configuration.setTickerFactory(() -> ticker::read);
    return configuration;
  }

  /* --------------- get (loading) --------------- */

  @Test
  public void get_loading_absent() {
    assertThat(jcacheLoading.get(KEY_1), is(KEY_1));
    Expirable<Integer> expirable = getExpirable(jcacheLoading, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void get_loading_expired() {
    jcacheLoading.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcacheLoading.get(KEY_1), is(KEY_1));
    Expirable<Integer> expirable = getExpirable(jcacheLoading, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void get_loading_present() {
    jcacheLoading.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcacheLoading.get(KEY_1), is(VALUE_1));
    Expirable<Integer> expirable = getExpirable(jcacheLoading, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(START_TIME_MS + EXPIRY_DURATION));
  }

  /* --------------- getAndPut --------------- */

  @Test
  public void getAndPut_absent() {
    assertThat(jcache.getAndPut(KEY_1, VALUE_1), is(nullValue()));

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void getAndPut_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.getAndPut(KEY_1, VALUE_1), is(nullValue()));
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void getAndPut_present() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.getAndPut(KEY_1, VALUE_2), is(VALUE_1));
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(START_TIME_MS + EXPIRY_DURATION));
  }

  /* --------------- put --------------- */

  @Test
  public void put_absent() {
    jcache.put(KEY_1, VALUE_1);

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void put_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    jcache.put(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void put_present() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    jcache.put(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(START_TIME_MS + EXPIRY_DURATION));
  }

  /* --------------- putAll --------------- */

  @Test
  public void putAll_absent() {
    jcache.putAll(entries);

    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
    }
  }

  @Test
  public void putAll_expired() {
    jcache.putAll(entries);
    advancePastExpiry();

    jcache.putAll(entries);
    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
    }
  }

  @Test
  public void putAll_present() {
    jcache.putAll(entries);
    advanceHalfExpiry();

    jcache.putAll(entries);
    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(START_TIME_MS + EXPIRY_DURATION));
    }
  }

  /* --------------- putIfAbsent --------------- */

  @Test
  public void putIfAbsent_absent() {
    jcache.putIfAbsent(KEY_1, VALUE_1);

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void putIfAbsent_expired() {
    jcache.putIfAbsent(KEY_1, VALUE_1);
    advancePastExpiry();

    jcache.putIfAbsent(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void putIfAbsent_present() {
    jcache.putIfAbsent(KEY_1, VALUE_1);
    advanceHalfExpiry();

    jcache.putIfAbsent(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(START_TIME_MS + EXPIRY_DURATION));
  }

  /* --------------- invoke --------------- */

  @Test
  public void invoke_absent() {
    assertThat(jcache.invoke(KEY_1, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    }), is(nullValue()));

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void invoke_expired() {
    jcache.put(KEY_1, VALUE_1);
    advancePastExpiry();

    assertThat(jcache.invoke(KEY_1, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    }), is(nullValue()));

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void invoke_present() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.invoke(KEY_1, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    }), is(nullValue()));

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(START_TIME_MS + EXPIRY_DURATION));
  }

  /* --------------- invokeAll --------------- */

  @Test
  public void invokeAll_absent() {
    assertThat(jcache.invokeAll(keys, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    }), is(anEmptyMap()));

    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
    }
  }

  @Test
  public void invokeAll_expired() {
    jcache.putAll(entries);
    advancePastExpiry();

    assertThat(jcache.invokeAll(keys, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    }), is(anEmptyMap()));

    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
    }
  }

  @Test
  public void invokeAll_present() {
    jcache.putAll(entries);
    advanceHalfExpiry();

    assertThat(jcache.invokeAll(keys, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    }), is(anEmptyMap()));

    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(START_TIME_MS + EXPIRY_DURATION));
    }
  }
}
