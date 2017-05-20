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
    CaffeineConfiguration<Integer, Integer> configuration = new CaffeineConfiguration<>();
    configuration.setExpiryPolicyFactory(() -> new ModifiedExpiryPolicy(
        new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION)));
    configuration.setTickerFactory(() -> ticker::read);
    return configuration;
  }

  @Test
  public void getAndPut() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    jcache.getAndPut(KEY_1, VALUE_1);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void getAndReplace() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.getAndReplace(KEY_1, VALUE_2), is(VALUE_1));
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void put() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    jcache.put(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void putAll() {
    jcache.putAll(entries);
    advanceHalfExpiry();

    jcache.putAll(entries);
    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
    }
  }

  @Test
  public void replace() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    jcache.replace(KEY_1, VALUE_2);
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void replaceConditionally() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.replace(KEY_1, VALUE_1, VALUE_2), is(true));
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void replaceConditionally_failed() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.replace(KEY_1, VALUE_2, VALUE_3), is(false));
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(START_TIME_MS + EXPIRY_DURATION));
  }

  @Test
  public void invoke() {
    jcache.put(KEY_1, VALUE_1);
    advanceHalfExpiry();

    assertThat(jcache.invoke(KEY_1, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    }), is(nullValue()));

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test
  public void invokeAll() {
    jcache.putAll(entries);
    advanceHalfExpiry();

    assertThat(jcache.invokeAll(keys, (entry, args) -> {
      entry.setValue(VALUE_2);
      return null;
    }), is(anEmptyMap()));

    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
    }
  }
}
