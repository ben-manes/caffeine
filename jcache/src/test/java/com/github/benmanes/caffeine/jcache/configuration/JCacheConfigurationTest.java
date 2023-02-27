/*
 * Copyright 2017 Nick Robison. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.configuration;

import static com.google.common.truth.Truth8.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.function.Supplier;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.testing.EqualsTester;

/**
 * @author Nick Robison (github.com/nickrobison)
 */
public final class JCacheConfigurationTest {
  private static final String PROVIDER_NAME = CaffeineCachingProvider.class.getName();

  private MutableConfiguration<String, String> cacheConfig;
  private CacheManager cacheManager;

  @BeforeClass
  public void beforeClass() {
    var provider = Caching.getCachingProvider(PROVIDER_NAME);
    cacheManager = provider.getCacheManager();
    cacheManager.getCacheNames().forEach(cacheManager::destroyCache);

    cacheConfig = new MutableConfiguration<>();
    cacheConfig.setTypes(String.class, String.class);
    cacheConfig.setStatisticsEnabled(true);
  }

  @Test
  public void equality() {
    new EqualsTester()
        .addEqualityGroup(cacheConfig,
            new MutableConfiguration<>(new CaffeineConfiguration<>(cacheConfig)))
        .addEqualityGroup(new CaffeineConfiguration<>(cacheConfig))
    .testEquals();
  }

  @Test
  public void anonymousCache() {
    checkConfiguration(() ->
        cacheManager.createCache("cache-not-in-config-file", cacheConfig), 500L);
    checkConfiguration(() ->
        cacheManager.getCache("cache-not-in-config-file", String.class, String.class), 500L);
  }

  @Test
  public void definedCache() {
    assertThrows(CacheException.class, () ->
        cacheManager.createCache("test-cache-2", cacheConfig));

    checkConfiguration(() ->
        cacheManager.getCache("test-cache-2", String.class, Integer.class), 1000L);
  }

  private void checkConfiguration(Supplier<Cache<?, ?>> cacheSupplier, long expectedValue) {
    Cache<?, ?> cache = cacheSupplier.get();

    @SuppressWarnings("unchecked")
    CaffeineConfiguration<?, ?> configuration =
        cache.getConfiguration(CaffeineConfiguration.class);
    assertThat(configuration.getMaximumSize()).hasValue(expectedValue);
  }
}
