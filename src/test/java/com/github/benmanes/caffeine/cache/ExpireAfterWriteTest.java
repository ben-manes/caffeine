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

import java.util.concurrent.TimeUnit;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Advanced.Expiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.ExpireAfterWrite;

/**
 * The test cases for caches that support the expire after write (time-to-live) policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterWriteTest {

  /* ---------------- Advanced -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter_write(
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter_write(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(2L));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.size(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf_write(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).get(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).get(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).isPresent(), is(false));
  }
}
