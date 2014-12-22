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

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.CacheSpec.Population;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class CacheTest {

  @CacheSpec
  @Test(dataProvider = "caches")
  public void getIfPresent_notFound(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getIfPresent(context.getAbsentKey());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void getIfPresent_found(Cache<Integer, Integer> cache, CacheContext context) {
    cache.getIfPresent(context.getFirstKey());
    cache.getIfPresent(context.getMiddleKey());
    cache.getIfPresent(context.getLastKey());
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void invalidateAll(Cache<Integer, Integer> cache) {
    cache.invalidateAll();
    assertThat(cache.size(), is(0L));
  }
}
