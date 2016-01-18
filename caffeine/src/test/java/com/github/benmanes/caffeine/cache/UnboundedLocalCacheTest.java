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

import java.util.Optional;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;

/**
 * The test cases for the implementation details of {@link UnboundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class UnboundedLocalCacheTest {

  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      refreshAfterWrite = Expire.DISABLED, keys = ReferenceType.STRONG,
      values = ReferenceType.STRONG)
  @Test(dataProvider = "caches")
  public void noPolicy(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.policy().eviction(), is(Optional.empty()));
    assertThat(cache.policy().expireAfterWrite(), is(Optional.empty()));
    assertThat(cache.policy().expireAfterAccess(), is(Optional.empty()));
    assertThat(cache.policy().refreshAfterWrite(), is(Optional.empty()));
  }

  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      refreshAfterWrite = Expire.DISABLED, keys = ReferenceType.STRONG,
      values = ReferenceType.STRONG)
  @Test(dataProvider = "caches")
  public void noPolicy_async(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.synchronous().policy().eviction(), is(Optional.empty()));
    assertThat(cache.synchronous().policy().expireAfterWrite(), is(Optional.empty()));
    assertThat(cache.synchronous().policy().expireAfterAccess(), is(Optional.empty()));
    assertThat(cache.synchronous().policy().refreshAfterWrite(), is(Optional.empty()));
  }
}
