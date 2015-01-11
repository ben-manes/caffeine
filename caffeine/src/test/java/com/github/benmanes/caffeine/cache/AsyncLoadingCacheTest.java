/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.testing.HasStats.hasHitCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadFailureCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadSuccessCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasMissCount;
import static com.github.benmanes.caffeine.matchers.IsFutureValue.futureOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.is;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;

/**
 * The test cases for the {@link AsyncLoadingCache} interface that simulate the most generic usages.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class AsyncLoadingCacheTest {

  /* ---------------- getFunc -------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullKey(AsyncLoadingCache<Integer, Integer> cache) {
    cache.get(null, key -> CompletableFuture.completedFuture(null));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullLoader(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.get(context.absentKey(), null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void getFunc_nullKeyAndLoader(AsyncLoadingCache<Integer, Integer> cache) {
    cache.get(null, null);
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = IllegalStateException.class)
  public void getFunc_throwsException(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    cache.get(context.absentKey(), key -> { throw new IllegalStateException(); });

    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(1)));
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getFunc_absent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    CompletableFuture<Integer> value = cache.get(key,
        k -> CompletableFuture.completedFuture(context.absentValue()));
    assertThat(value, is(futureOf(context.absentValue())));
    assertThat(context, both(hasMissCount(1)).and(hasHitCount(0)));
    assertThat(context, both(hasLoadSuccessCount(1)).and(hasLoadFailureCount(0)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getFunc_present(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, CompletableFuture<Integer>> loader = key -> { throw new RuntimeException(); };
    assertThat(cache.get(context.firstKey(), loader),
        is(futureOf(context.original().get(context.firstKey()))));
    assertThat(cache.get(context.middleKey(), loader),
        is(futureOf(context.original().get(context.middleKey()))));
    assertThat(cache.get(context.lastKey(), loader),
        is(futureOf(context.original().get(context.lastKey()))));

    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }

  /* ---------------- get -------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(AsyncLoadingCache<Integer, Integer> cache) {
    cache.get(null);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey()), is(futureOf(context.absentValue())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.EXCEPTIONAL)
  public void get_absent_failure(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey()).isCompletedExceptionally(), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.get(context.firstKey()), futureOf(-context.firstKey()));
    assertThat(cache.get(context.middleKey()), futureOf(-context.middleKey()));
    assertThat(cache.get(context.lastKey()), futureOf(-context.lastKey()));
    assertThat(context, both(hasMissCount(0)).and(hasHitCount(3)));
    assertThat(context, both(hasLoadSuccessCount(0)).and(hasLoadFailureCount(0)));
  }
}
