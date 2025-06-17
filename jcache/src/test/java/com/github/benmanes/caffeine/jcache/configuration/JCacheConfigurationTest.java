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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.jcache.copy.Copier;
import com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopier;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.testing.EqualsTester;

/**
 * @author Nick Robison (github.com/nickrobison)
 */
public final class JCacheConfigurationTest {
  private static final String PROVIDER_NAME = CaffeineCachingProvider.class.getName();

  private MutableConfiguration<String, String> cacheConfig;
  private CachingProvider cachingProvider;
  private CacheManager cacheManager;

  @BeforeClass
  public void beforeClass() {
    cachingProvider = Caching.getCachingProvider(PROVIDER_NAME);
    cacheManager = cachingProvider.getCacheManager();
    cacheManager.getCacheNames().forEach(cacheManager::destroyCache);

    cacheConfig = new MutableConfiguration<>();
    cacheConfig.setTypes(String.class, String.class);
    cacheConfig.setStatisticsEnabled(true);
  }

  @AfterClass
  public void afterClass() {
    cachingProvider.close();
    cacheManager.close();
  }

  @Test
  public void setMaximumSize() {
    var config = new CaffeineConfiguration<>();
    checkOptionalLongSetting(config::getMaximumSize, config::setMaximumSize);
  }

  @Test
  public void setMaximumWeight() {
    var config = new CaffeineConfiguration<>();
    checkOptionalLongSetting(config::getMaximumWeight, config::setMaximumWeight);
  }

  @Test
  public void setExpireAfterAccess() {
    var config = new CaffeineConfiguration<>();
    checkOptionalLongSetting(config::getExpireAfterAccess, config::setExpireAfterAccess);
  }

  @Test
  public void setExpireAfterWrite() {
    var config = new CaffeineConfiguration<>();
    checkOptionalLongSetting(config::getExpireAfterWrite, config::setExpireAfterWrite);
  }

  private static void checkOptionalLongSetting(
      Supplier<OptionalLong> getter, Consumer<OptionalLong> setter) {
    setter.accept(OptionalLong.of(1_000));
    assertThat(getter.get()).hasValue(1_000);

    setter.accept(OptionalLong.empty());
    assertThat(getter.get()).isEmpty();
  }

  @Test
  public void setRefreshAfterWrite() {
    var config = new CaffeineConfiguration<>();
    config.setRefreshAfterWrite(OptionalLong.of(Duration.ofSeconds(1).toNanos()));
    assertThat(config.getRefreshAfterWrite()).hasValue(Duration.ofSeconds(1).toNanos());

    config.setRefreshAfterWrite(OptionalLong.empty());
    assertThat(config.getRefreshAfterWrite()).isEmpty();
  }

  @Test
  public void equality() {
    var tester = new EqualsTester()
        .addEqualityGroup(cacheConfig,
            new MutableConfiguration<>(new CaffeineConfiguration<>(cacheConfig)))
        .addEqualityGroup(new CaffeineConfiguration<>(cacheConfig));
    var configurations = Lists.cartesianProduct(IntStream.range(0, 10)
        .mapToObj(i -> ImmutableList.of(true, false)).collect(toImmutableList()));
    for (var config : configurations) {
      var absent = OptionalLong.empty();
      var configuration = new CaffeineConfiguration<>(cacheConfig);
      configuration.setExpireAfterAccess(config.get(0) ? OptionalLong.of(1_000) : absent);
      configuration.setExpireAfterWrite(config.get(1) ? OptionalLong.of(2_000) : absent);
      configuration.setRefreshAfterWrite(config.get(2) ? OptionalLong.of(3_000) : absent);
      configuration.setMaximumWeight(config.get(3) ? OptionalLong.of(4_000) : absent);
      configuration.setMaximumSize(config.get(4) ? OptionalLong.of(5_000) : absent);
      configuration.setWeigherFactory(config.get(5)
          ? Optional.of(Weigher::singletonWeigher)
          : Optional.empty());
      configuration.setTickerFactory(config.get(6)
          ? Ticker::systemTicker
          : Ticker::disabledTicker);
      configuration.setCopierFactory(config.get(7)
          ? JavaSerializationCopier::new
          : Copier::identity);
      configuration.setExecutorFactory(config.get(8)
          ? ForkJoinPool::commonPool
          : () -> Runnable::run);
      configuration.setSchedulerFactory(config.get(9)
          ? Scheduler::systemScheduler
          : Scheduler::disabledScheduler);
      tester.addEqualityGroup(config);
      tester.addEqualityGroup(new CaffeineConfiguration<>(configuration));
    }
    tester.testEquals();
  }

  @Test
  public void anonymousCache() {
    checkConfiguration(cacheManager.createCache("cache-not-in-config-file", cacheConfig), 500L);
    checkConfiguration(cacheManager.getCache(
        "cache-not-in-config-file", String.class, String.class), 500L);
  }

  @Test
  public void definedCache() {
    assertThrows(CacheException.class, () -> cacheManager.createCache("test-cache-2", cacheConfig));
    checkConfiguration(cacheManager.getCache("test-cache-2", String.class, Integer.class), 1000L);
  }

  private static void checkConfiguration(Cache<?, ?> cache, long expectedValue) {
    @SuppressWarnings("unchecked")
    CaffeineConfiguration<?, ?> configuration =
        cache.getConfiguration(CaffeineConfiguration.class);
    assertThat(configuration.getMaximumSize()).hasValue(expectedValue);
  }
}
