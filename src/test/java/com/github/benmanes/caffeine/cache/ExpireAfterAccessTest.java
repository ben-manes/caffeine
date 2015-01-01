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

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.concurrent.TimeUnit;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;

/**
 * The test cases for caches that support the expire after read (time-to-idle) policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterAccessTest {

  @Test(enabled = false, dataProvider = "caches")
  @CacheSpec(expireAfterAccess = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getIfPresent(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(context.firstKey()), is(-context.firstKey()));
    assertThat(cache.getIfPresent(context.lastKey()), is(nullValue()));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPIRED));
  }
}
