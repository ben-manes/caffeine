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
import javax.cache.configuration.MutableConfiguration;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.github.benmanes.caffeine.jcache.copy.Copier;
import com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.testing.EqualsTester;

/**
 * @author Nick Robison (github.com/nickrobison)
 */
final class JCacheConfigurationTest {

  @Test
  void setMaximumSize() {
    var config = new CaffeineConfiguration<>();
    checkOptionalLongSetting(config::getMaximumSize, config::setMaximumSize);
  }

  @Test
  void setMaximumWeight() {
    var config = new CaffeineConfiguration<>();
    checkOptionalLongSetting(config::getMaximumWeight, config::setMaximumWeight);
  }

  @Test
  void setExpireAfterAccess() {
    var config = new CaffeineConfiguration<>();
    checkOptionalLongSetting(config::getExpireAfterAccess, config::setExpireAfterAccess);
  }

  @Test
  void setExpireAfterWrite() {
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
  void setRefreshAfterWrite() {
    var config = new CaffeineConfiguration<>();
    config.setRefreshAfterWrite(OptionalLong.of(Duration.ofSeconds(1).toNanos()));
    assertThat(config.getRefreshAfterWrite()).hasValue(Duration.ofSeconds(1).toNanos());

    config.setRefreshAfterWrite(OptionalLong.empty());
    assertThat(config.getRefreshAfterWrite()).isEmpty();
  }

  @Test
  @SuppressWarnings("SequencedCollectionGetFirst")
  void equality() {
    var cacheConfig = new MutableConfiguration<String, String>()
        .setTypes(String.class, String.class)
        .setStatisticsEnabled(true);
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
  void anonymousCache() {
    try (var fixture = JCacheFixture.builder().build()) {
      var cacheConfig = new MutableConfiguration<String, String>()
          .setTypes(String.class, String.class)
          .setStatisticsEnabled(true);
      checkConfiguration(fixture.cacheManager().createCache(
          "cache-not-in-config-file", cacheConfig), 500L);
      checkConfiguration(fixture.cacheManager().getCache(
          "cache-not-in-config-file", String.class, String.class), 500L);
    }
  }

  @Test
  void definedCache() {
    try (var fixture = JCacheFixture.builder().build()) {
      var cacheConfig = new MutableConfiguration<String, String>()
          .setTypes(String.class, String.class)
          .setStatisticsEnabled(true);
      assertThrows(CacheException.class, () ->
          fixture.cacheManager().createCache("test-cache-2", cacheConfig));
      checkConfiguration(fixture.cacheManager()
          .getCache("test-cache-2", String.class, Integer.class), 1000L);
    }
  }

  private static void checkConfiguration(Cache<?, ?> cache, long expectedValue) {
    @SuppressWarnings("unchecked")
    CaffeineConfiguration<?, ?> configuration =
        cache.getConfiguration(CaffeineConfiguration.class);
    assertThat(configuration.getMaximumSize()).hasValue(expectedValue);
  }
}
