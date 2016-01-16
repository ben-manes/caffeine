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
package com.github.benmanes.caffeine.jcache;

import java.io.Serializable;

import javax.annotation.Nullable;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;

/**
 * A testing harness for simplifying the unit tests.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
public abstract class AbstractJCacheTest {
  protected LoadingCacheProxy<Integer, Integer> jcacheLoading;
  protected CacheProxy<Integer, Integer> jcache;
  protected CacheManager cacheManager;
  protected JFakeTicker ticker;

  @BeforeClass(alwaysRun = true)
  public void beforeClass() {
    CachingProvider provider = Caching.getCachingProvider(CaffeineCachingProvider.class.getName());
    cacheManager = provider.getCacheManager(
        provider.getDefaultURI(), provider.getDefaultClassLoader());
  }

  @BeforeMethod(alwaysRun = true)
  public void before() {
    ticker = new JFakeTicker();
    jcache = (CacheProxy<Integer, Integer>) cacheManager.createCache("jcache", getConfiguration());
    jcacheLoading = (LoadingCacheProxy<Integer, Integer>) cacheManager.createCache(
        "jcacheLoading", getLoadingConfiguration());
  }

  @AfterMethod(alwaysRun = true)
  public void after() {
    cacheManager.destroyCache("jcache");
    cacheManager.destroyCache("jcacheLoading");
  }

  @Nullable
  public Expirable<Integer> getExpirable(Integer key) {
    return jcache.cache.getIfPresent(key);
  }

  /** The base configuration used by the test. */
  protected abstract CaffeineConfiguration<Integer, Integer> getConfiguration();

  /** The loading configuration used by the test. */
  protected abstract CaffeineConfiguration<Integer, Integer> getLoadingConfiguration();

  protected final class JFakeTicker extends com.google.common.testing.FakeTicker
      implements Ticker, Serializable {
    private static final long serialVersionUID = 1L;
  }
}
