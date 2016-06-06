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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.lang.management.ManagementFactory;
import java.util.function.Supplier;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.spi.CachingProvider;
import javax.management.ObjectName;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.typesafe.config.ConfigFactory;

/**
 * @author eiden (Christoffer Eide)
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheManagerTest {
  private static final String PROVIDER_NAME = CaffeineCachingProvider.class.getName();

  private CacheManager cacheManager;

  @BeforeClass
  public void beforeClass() {
    CachingProvider provider = Caching.getCachingProvider(PROVIDER_NAME);
    cacheManager = provider.getCacheManager(
        provider.getDefaultURI(), provider.getDefaultClassLoader());
  }

  @Test
  public void jmxBeanIsRegistered_createCache() throws Exception {
    checkConfigurationJmx(() -> cacheManager.createCache("cache-not-in-config-file",
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache").get()));
  }

  @Test
  public void jmxBeanIsRegistered_getCache() throws Exception {
    checkConfigurationJmx(() -> cacheManager.getCache("test-cache"));
  }

  private void checkConfigurationJmx(Supplier<Cache<?, ?>> cacheSupplier) throws Exception {
    Cache<?, ?> cache = cacheSupplier.get();

    @SuppressWarnings("unchecked")
    CompleteConfiguration<?, ?> configuration = cache.getConfiguration(CompleteConfiguration.class);
    assertThat(configuration.isManagementEnabled(), is(true));
    assertThat(configuration.isStatisticsEnabled(), is(true));

    String name = "javax.cache:Cache=%s,CacheManager=%s,type=CacheStatistics";
    ManagementFactory.getPlatformMBeanServer().getObjectInstance(
        new ObjectName(String.format(name, cache.getName(), PROVIDER_NAME)));
  }
}
