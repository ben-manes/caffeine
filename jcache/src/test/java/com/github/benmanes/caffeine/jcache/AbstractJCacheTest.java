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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.integration.CacheLoader;
import javax.cache.spi.CachingProvider;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.testing.FakeTicker;

/**
 * A testing harness for simplifying the unit tests.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
@SuppressWarnings("PreferJavaTimeOverload")
public abstract class AbstractJCacheTest {
  protected static final long START_TIME_MS = 0L;//System.currentTimeMillis();
  protected static final long EXPIRY_DURATION = TimeUnit.MINUTES.toMillis(1);

  protected static final Integer KEY_1 = 1, VALUE_1 = -1;
  protected static final Integer KEY_2 = 2, VALUE_2 = -2;
  protected static final Integer KEY_3 = 3, VALUE_3 = -3;

  protected final Set<Integer> keys = ImmutableSet.of(KEY_1, KEY_2, KEY_3);
  protected final Map<Integer, Integer> entries = ImmutableMap.of(
      KEY_1, VALUE_1, KEY_2, VALUE_2, KEY_3, VALUE_3);

  protected LoadingCacheProxy<Integer, Integer> jcacheLoading;
  protected CacheProxy<Integer, Integer> jcache;
  protected CacheManager cacheManager;
  protected FakeTicker ticker;

  @BeforeClass(alwaysRun = true)
  public void beforeClass() {
    CachingProvider provider = Caching.getCachingProvider(CaffeineCachingProvider.class.getName());
    cacheManager = provider.getCacheManager(
        provider.getDefaultURI(), provider.getDefaultClassLoader());
  }

  @BeforeMethod(alwaysRun = true)
  public void before() {
    ticker = new FakeTicker().advance(START_TIME_MS, TimeUnit.MILLISECONDS);
    jcache = (CacheProxy<Integer, Integer>) cacheManager.createCache("jcache", getConfiguration());
    jcacheLoading = (LoadingCacheProxy<Integer, Integer>) cacheManager.createCache(
        "jcacheLoading", getLoadingConfiguration());
  }

  @AfterMethod(alwaysRun = true)
  public void after() {
    cacheManager.destroyCache("jcache");
    cacheManager.destroyCache("jcacheLoading");
  }

  /** The base configuration used by the test. */
  protected abstract CaffeineConfiguration<Integer, Integer> getConfiguration();

  /* --------------- Utility methods ------------- */

  @Nullable
  protected static Expirable<Integer> getExpirable(
      CacheProxy<Integer, Integer> cache, Integer key) {
    return cache.cache.getIfPresent(key);
  }

  protected void advanceHalfExpiry() {
    ticker.advance(EXPIRY_DURATION / 2, TimeUnit.MILLISECONDS);
  }

  protected void advancePastExpiry() {
    ticker.advance(2 * EXPIRY_DURATION, TimeUnit.MILLISECONDS);
  }

  /** @return the current time in milliseconds */
  protected final long currentTimeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(ticker.read());
  }

  /** The loading configuration used by the test. */
  protected CaffeineConfiguration<Integer, Integer> getLoadingConfiguration() {
    CaffeineConfiguration<Integer, Integer> configuration = getConfiguration();
    configuration.setCacheLoaderFactory(this::getCacheLoader);
    configuration.setReadThrough(true);
    return configuration;
  }

  /** The cache loader used by the test. */
  protected CacheLoader<Integer, Integer> getCacheLoader() {
    return new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        return key;
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        return Maps.asMap(ImmutableSet.copyOf(keys), this::load);
      }
    };
  }
}
