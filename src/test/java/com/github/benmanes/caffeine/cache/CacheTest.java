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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.concurrent.ExecutionException;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;

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
    assertThat(cache.getIfPresent(context.getAbsentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_present(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.getIfPresent(context.getFirstKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.getMiddleKey()), is(not(nullValue())));
    assertThat(cache.getIfPresent(context.getLastKey()), is(not(nullValue())));
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getIfPresent_nullKey(Cache<Integer, Integer> cache) {
    cache.getIfPresent(null);
  }

  /* ---------------- get -------------- */

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKey(Cache<Integer, Integer> cache) throws ExecutionException {
    cache.get(null, () -> 0);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullLoader(Cache<Integer, Integer> cache, CacheContext context)
      throws ExecutionException {
    cache.get(context.getAbsentKey(), null);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_nullKeyAndLoader(Cache<Integer, Integer> cache) throws ExecutionException {
    cache.get(null, null);
  }

  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  @Test(enabled = false, dataProvider = "caches", expectedExceptions = ExecutionException.class)
  public void get_throwsException(Cache<Integer, Integer> cache, CacheContext context)
      throws ExecutionException {
    cache.get(context.getAbsentKey(), () -> { throw new Exception(); });
  }

  /* ---------------- invalidate -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void invalidate_absent(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(context.getAbsentKey());
    assertThat(cache.size(), is(context.getInitialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void invalidate_present(Cache<Integer, Integer> cache, CacheContext context) {
    cache.invalidate(context.getFirstKey());
    cache.invalidate(context.getMiddleKey());
    cache.invalidate(context.getLastKey());

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
