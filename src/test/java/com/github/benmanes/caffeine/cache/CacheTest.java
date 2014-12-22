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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.concurrent.ExecutionException;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.CacheSpec.Population;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class CacheTest {

  /* ---------------- getIfPresent -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getIfPresent_absent(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.getAbsentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.getFirstKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.getMiddleKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.getLastKey()), is(not(nullValue())));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresent_nullKey(Cache<Integer, Integer> cache) {
    cache.getIfPresent(null);
  }

  /* ---------------- get -------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKey(Cache<Integer, Integer> cache) throws ExecutionException {
    cache.get(null, () -> 0);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullLoader(Cache<Integer, Integer> cache, CacheContext context)
      throws ExecutionException {
    cache.get(context.getAbsentKey(), null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKeyAndLoader(Cache<Integer, Integer> cache) throws ExecutionException {
    cache.get(null, null);
  }

  @CacheSpec
  @Test(enabled = false, dataProvider = "caches", expectedExceptions = ExecutionException.class)
  public void get_throwsException(Cache<Integer, Integer> cache, CacheContext context)
      throws ExecutionException {
    cache.get(context.getAbsentKey(), () -> { throw new Exception(); });
  }

  /* ---------------- invalidate -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void invalidate_absent(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(context.getAbsentKey());
    assertThat(cache.size(), is(context.getInitialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidate_present(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(context.firstKey);
    assertThat(cache.size(), is(Math.max(0, context.getInitialSize() - 1)));
    cache.invalidate(context.midKey);
    assertThat(cache.size(), is(Math.max(0, context.getInitialSize() - 2)));
    cache.invalidate(context.lastKey);
    assertThat(cache.size(), is(Math.max(0, context.getInitialSize() - 3)));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void invalidate_nullKey(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(null);
  }

  /* ---------------- invalidateAll -------------- */

  @CacheSpec
  @Test(dataProvider = "caches")
  public void invalidateAll(Cache<Integer, Integer> cache) {
    cache.invalidateAll();
    assertThat(cache.size(), is(0L));
  }
}
