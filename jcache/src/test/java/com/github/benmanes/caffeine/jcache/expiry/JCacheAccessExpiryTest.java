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

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.cache.expiry.AccessedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.processor.EntryProcessorResult;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.Expirable;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

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
    CaffeineConfiguration<Integer, Integer> configuration = new CaffeineConfiguration<>();
    configuration.setExpiryPolicyFactory(() -> new AccessedExpiryPolicy(
        new Duration(TimeUnit.MILLISECONDS, EXPIRY_DURATION)));
    configuration.setTickerFactory(() -> ticker::read);
    return configuration;
  }

  @DataProvider(name = "eternal")
  public Object[] providesEternal() {
    return new Object[] { true, false };
  }

  /* --------------- get --------------- */

  @Test
  public void get_absent() {
    assertThat(jcache.get(KEY_1), is(VALUE_1));

    advancePastExpiry();
    assertThat(jcache.get(KEY_1), is(nullValue()));
    assertThat(getExpirable(jcache, KEY_1), is(nullValue()));
  }

  @Test(dataProvider = "eternal")
  public void get_present(boolean eternal) {
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    if (eternal) {
      expirable.setExpireTimeMS(Long.MAX_VALUE);
    }

    assertThat(jcache.get(KEY_1), is(VALUE_1));
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  /* --------------- get (loading) --------------- */

  @Test
  public void get_loading_absent() {
    assertThat(jcacheLoading.get(KEY_1), is(VALUE_1));

    advancePastExpiry();
    assertThat(jcacheLoading.get(KEY_1), is(KEY_1));

    Expirable<Integer> expirable = getExpirable(jcacheLoading, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  @Test(dataProvider = "eternal")
  public void get_loading_present(boolean eternal) {
    Expirable<Integer> expirable = getExpirable(jcacheLoading, KEY_1);
    if (eternal) {
      expirable.setExpireTimeMS(Long.MAX_VALUE);
    }

    assertThat(jcacheLoading.get(KEY_1), is(VALUE_1));
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  /* --------------- getAllPresent --------------- */

  @Test
  public void getAll_absent() {
    assertThat(jcache.getAll(keys), is(entries));

    advancePastExpiry();
    assertThat(jcache.getAll(keys), is(anEmptyMap()));

    for (Integer key : keys) {
      assertThat(getExpirable(jcache, key), is(nullValue()));
    }
  }

  @Test(dataProvider = "eternal")
  public void getAll_present(boolean eternal) {
    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcacheLoading, key);
      if (eternal) {
        expirable.setExpireTimeMS(Long.MAX_VALUE);
      }
    }

    assertThat(jcache.getAll(keys), is(entries));

    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
    }
  }

  /* --------------- invoke --------------- */

  @Test
  public void invoke_absent() {
    assertThat(jcache.get(KEY_1), is(VALUE_1));

    advancePastExpiry();
    assertThat(jcache.invoke(KEY_1, (entry, args) -> entry.getValue()), is(nullValue()));

    assertThat(getExpirable(jcache, KEY_1), is(nullValue()));
  }

  @Test(dataProvider = "eternal")
  public void invoke_present(boolean eternal) {
    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    if (eternal) {
      expirable.setExpireTimeMS(Long.MAX_VALUE);
    }

    assertThat(jcache.invoke(KEY_1, (entry, args) -> entry.getValue()), is(VALUE_1));
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  /* --------------- invokeAll --------------- */

  @Test
  public void invokeAll_absent() {
    assertThat(jcache.getAll(keys), is(entries));

    advancePastExpiry();
    assertThat(jcache.invokeAll(keys, (entry, args) -> entry.getValue()), is(anEmptyMap()));

    for (Integer key : keys) {
      assertThat(getExpirable(jcache, key), is(nullValue()));
    }
  }

  @Test
  public void invokeAll_present() {
    Map<Integer, EntryProcessorResult<Integer>> result =
        jcache.invokeAll(keys, (entry, args) -> entry.getValue());
    Map<Integer, Integer> unwrapped = result.entrySet().stream().collect(
        Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    assertThat(unwrapped, is(entries));

    for (Integer key : keys) {
      Expirable<Integer> expirable = getExpirable(jcache, key);
      assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
    }
  }

  /* --------------- conditional remove --------------- */

  @Test
  public void removeConditionally() {
    assertThat(jcache.remove(KEY_1, VALUE_2), is(false));

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }

  /* --------------- conditional replace --------------- */

  @Test
  public void replaceConditionally() {
    assertThat(jcache.replace(KEY_1, VALUE_2, VALUE_3), is(false));

    Expirable<Integer> expirable = getExpirable(jcache, KEY_1);
    assertThat(expirable.getExpireTimeMS(), is(currentTimeMillis() + EXPIRY_DURATION));
  }
}
