/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.matchers.HasConsumedEvicted.consumedEvicted;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.collect.ImmutableList;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class CacheTest {

  /* ---------------- getIfPresent -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches")
  public void getIfPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.firstKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.lastKey()), is(not(nullValue())));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresent_nullKey(Cache<Integer, Integer> cache) {
    cache.getIfPresent(null);
  }

  /* ---------------- get -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(Cache<Integer, Integer> cache, CacheContext context) throws Exception {
    Integer key = context.absentKey();
    Integer value = cache.get(key, () -> -key);
    assertThat(value, is(-key));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(Cache<Integer, Integer> cache, CacheContext context) throws Exception {
    Callable<Integer> loader = () -> { throw new Exception(); };
    assertThat(cache.get(context.firstKey(), loader), is(-context.firstKey()));
    assertThat(cache.get(context.middleKey(), loader), is(-context.middleKey()));
    assertThat(cache.get(context.lastKey(), loader), is(-context.lastKey()));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKey(Cache<Integer, Integer> cache) throws Exception {
    cache.get(null, () -> 0);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullLoader(Cache<Integer, Integer> cache, CacheContext context) throws Exception {
    cache.get(context.absentKey(), null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKeyAndLoader(Cache<Integer, Integer> cache) throws Exception {
    cache.get(null, null);
  }

  @CacheSpec
  @Test(enabled = false, dataProvider = "caches", expectedExceptions = ExecutionException.class)
  public void get_throwsException(Cache<Integer, Integer> cache, CacheContext context)
      throws Exception {
    cache.get(context.absentKey(), () -> { throw new Exception(); });
  }

  /* ---------------- getAllPresent -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(context.absentKeys());
    assertThat(result.size(), is(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_present_some(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> expect = new HashMap<>();
    expect.put(context.firstKey(), -context.firstKey());
    expect.put(context.middleKey(), -context.middleKey());
    expect.put(context.lastKey(), -context.lastKey());
    Map<Integer, Integer> result = cache.getAllPresent(expect.keySet());
    assertThat(result, is(equalTo(expect)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_present_all(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> result = cache.getAllPresent(cache.asMap().keySet());
    assertThat(result, is(equalTo(cache.asMap())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_iterable_empty(Cache<Integer, Integer> cache) {
    Map<Integer, Integer> result = cache.getAllPresent(ImmutableList.of());
    assertThat(result.size(), is(0));
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_iterable_nullKey(Cache<Integer, Integer> cache) {
    cache.getAllPresent(Collections.singletonList(null));
  }

  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_iterable_null(Cache<Integer, Integer> cache) {
    cache.getAllPresent(null);
  }

  /* ---------------- invalidate -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void invalidate_absent(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(context.absentKey());
    assertThat(cache.size(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidate_present(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(context.firstKey());
    cache.invalidate(context.middleKey());
    cache.invalidate(context.lastKey());

    assertThat(cache, consumedEvicted(context));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void invalidate_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(null);
  }

  /* ---------------- invalidateAll -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void invalidateAll(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidateAll();
    assertThat(cache.size(), is(0L));
    assertThat(cache, consumedEvicted(context));
  }
}
