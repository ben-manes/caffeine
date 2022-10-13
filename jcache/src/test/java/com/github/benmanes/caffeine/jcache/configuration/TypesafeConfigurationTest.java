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
package com.github.benmanes.caffeine.jcache.configuration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;

import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.cache.Cache;
import javax.cache.Caching;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopier;
import com.google.common.collect.Iterables;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TypesafeConfigurationTest {

  @Test
  public void configSource() {
    Config config = ConfigFactory.load();
    Supplier<Config> configSource = () -> config;
    TypesafeConfigurator.setConfigSource(configSource);
    assertThat(TypesafeConfigurator.configSource()).isEqualTo(configSource);
  }

  @Test
  public void defaults() {
    CaffeineConfiguration<Integer, Integer> defaults =
        TypesafeConfigurator.defaults(ConfigFactory.load());
    assertThat(defaults.getKeyType()).isEqualTo(Object.class);
    assertThat(defaults.getValueType()).isEqualTo(Object.class);
    assertThat(defaults.getExecutorFactory().create()).isEqualTo(ForkJoinPool.commonPool());
    assertThat(defaults.getMaximumSize()).hasValue(500);
  }

  @Test
  public void cacheNames() {
    assertThat(TypesafeConfigurator.cacheNames(ConfigFactory.empty())).isEmpty();;

    var names = TypesafeConfigurator.cacheNames(ConfigFactory.load());
    assertThat(names).containsExactly("default", "listeners", "osgi-cache",
        "invalid-cache", "test-cache", "test-cache-2", "guice");
  }

  @Test
  public void illegalPath() {
    assertThat(TypesafeConfigurator.from(ConfigFactory.load(), "#")).isEmpty();
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void invalidCache() {
    TypesafeConfigurator.from(ConfigFactory.load(), "invalid-cache");
  }

  @Test
  public void testCache() {
    Optional<CaffeineConfiguration<Integer, Integer>> config =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache");
    assertThat(config).isEqualTo(
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache"));
    checkTestCache(config.orElseThrow());
  }

  @Test
  public void testCache2() {
    Optional<CaffeineConfiguration<Integer, Integer>> config1 =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache");
    Optional<CaffeineConfiguration<Integer, Integer>> config2 =
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache-2");
    assertThat(config1).isNotEqualTo(config2);

    var config = config2.orElseThrow();
    assertThat(config.getKeyType()).isAssignableTo(String.class);
    assertThat(config.getValueType()).isAssignableTo(Integer.class);
    assertThat(config.isNativeStatisticsEnabled()).isFalse();
    assertThat(config.getExpiryPolicyFactory().create().getExpiryForAccess()).isNull();
    assertThat(config.getExpiryFactory().orElseThrow().create()).isInstanceOf(TestExpiry.class);
    assertThat(config.getExecutorFactory().create()).isEqualTo(ForkJoinPool.commonPool());
    assertThat(config.getCacheWriter()).isNull();
  }

  @Test
  public void getCache() {
    Cache<Integer, Integer> cache = Caching.getCachingProvider()
        .getCacheManager().getCache("test-cache");
    assertThat(cache).isNotNull();

    @SuppressWarnings("unchecked")
    CaffeineConfiguration<Integer, Integer> config =
        cache.getConfiguration(CaffeineConfiguration.class);
    checkTestCache(config);
  }

  static void checkTestCache(CaffeineConfiguration<?, ?> config) {
    checkStoreByValue(config);
    checkListener(config);

    assertThat(config.getKeyType()).isEqualTo(Object.class);
    assertThat(config.getValueType()).isEqualTo(Object.class);
    assertThat(config.getExecutorFactory().create()).isInstanceOf(TestExecutor.class);
    assertThat(config.getSchedulerFactory().create()).isInstanceOf(TestScheduler.class);
    assertThat(config.getCacheLoaderFactory().create()).isInstanceOf(TestCacheLoader.class);
    assertThat(config.getCacheWriter()).isInstanceOf(TestCacheWriter.class);
    assertThat(config.isNativeStatisticsEnabled()).isTrue();
    assertThat(config.isStatisticsEnabled()).isTrue();
    assertThat(config.isManagementEnabled()).isTrue();

    checkSize(config);
    checkRefresh(config);
    checkLazyExpiration(config);
    checkEagerExpiration(config);
  }

  static void checkStoreByValue(CaffeineConfiguration<?, ?> config) {
    assertThat(config.isStoreByValue()).isTrue();
    assertThat(config.getCopierFactory().create()).isInstanceOf(JavaSerializationCopier.class);
  }

  static void checkListener(CaffeineConfiguration<?, ?> config) {
    var listener = Iterables.getOnlyElement(config.getCacheEntryListenerConfigurations());
    assertThat(listener.getCacheEntryListenerFactory().create())
        .isInstanceOf(TestCacheEntryListener.class);
    assertThat(listener.getCacheEntryEventFilterFactory().create())
        .isInstanceOf(TestCacheEntryEventFilter.class);
    assertThat(listener.isSynchronous()).isTrue();
    assertThat(listener.isOldValueRequired()).isTrue();
  }

  static void checkLazyExpiration(CaffeineConfiguration<?, ?> config) {
    ExpiryPolicy expiry = config.getExpiryPolicyFactory().create();
    assertThat(expiry.getExpiryForCreation()).isEqualTo(Duration.ONE_MINUTE);
    assertThat(expiry.getExpiryForUpdate()).isEqualTo(Duration.FIVE_MINUTES);
    assertThat(expiry.getExpiryForAccess()).isEqualTo(Duration.TEN_MINUTES);
  }

  static void checkEagerExpiration(CaffeineConfiguration<?, ?> config) {
    assertThat(config.getExpireAfterWrite()).hasValue(TimeUnit.MINUTES.toNanos(1));
    assertThat(config.getExpireAfterAccess()).hasValue(TimeUnit.MINUTES.toNanos(5));
  }

  static void checkRefresh(CaffeineConfiguration<?, ?> config) {
    assertThat(config.getRefreshAfterWrite()).hasValue(TimeUnit.SECONDS.toNanos(30));
  }

  static void checkSize(CaffeineConfiguration<?, ?> config) {
    assertThat(config.getMaximumSize()).isEmpty();
    assertThat(config.getMaximumWeight()).hasValue(1_000L);
    assertThat(config.getWeigherFactory().orElseThrow().create()).isInstanceOf(TestWeigher.class);
  }
}
