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

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import javax.cache.CacheManager;
import javax.cache.integration.CacheLoader;
import javax.cache.spi.CachingProvider;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.testing.FakeTicker;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A testing harness for simplifying the unit tests.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheFixture implements AutoCloseable {
  private static final Duration ONE_MILLISECOND = Duration.ofMillis(1);
  public static final Duration EXPIRY_DURATION = Duration.ofMinutes(1);
  public static final Duration START_TIME = Duration.ofNanos(
      ThreadLocalRandom.current().nextLong(Long.MIN_VALUE, Long.MAX_VALUE));

  public static final Integer KEY_1 = 1;
  public static final Integer KEY_2 = 2;
  public static final Integer KEY_3 = 3;
  public static final Integer VALUE_1 = -1;
  public static final Integer VALUE_2 = -2;
  public static final Integer VALUE_3 = -3;

  public static final ImmutableMap<Integer, Integer> ENTRIES = ImmutableMap.of(
      KEY_1, VALUE_1, KEY_2, VALUE_2, KEY_3, VALUE_3);
  public static final ImmutableSet<Integer> KEYS = ENTRIES.keySet();

  private final CaffeineConfiguration<Integer, Integer> jcacheConfiguration;
  private final LoadingCacheProxy<Integer, Integer> jcacheLoading;
  private final CacheProxy<Integer, Integer> jcache;
  private final CachingProvider cachingProvider;
  private final CacheManager cacheManager;
  private final FakeTicker ticker;

  private JCacheFixture(Builder builder) {
    cachingProvider = new CaffeineCachingProvider();
    cacheManager = cachingProvider.getCacheManager(
        cachingProvider.getDefaultURI(), cachingProvider.getDefaultClassLoader());
    cacheManager.getCacheNames().forEach(cacheManager::destroyCache);
    ticker = builder.ticker;

    jcacheConfiguration = new CaffeineConfiguration<>(builder.base);
    builder.configurator.accept(jcacheConfiguration);
    jcache = (CacheProxy<Integer, Integer>) cacheManager.createCache("jcache", jcacheConfiguration);

    var jcacheLoadingConfig = new CaffeineConfiguration<>(jcacheConfiguration);
    builder.loading.accept(jcacheLoadingConfig);
    jcacheLoading = (LoadingCacheProxy<Integer, Integer>) cacheManager
        .createCache("jcacheLoading", jcacheLoadingConfig);
  }

  public CachingProvider cachingProvider() {
    return cachingProvider;
  }

  public CaffeineConfiguration<Integer, Integer> jcacheConfiguration() {
    return jcacheConfiguration;
  }

  public LoadingCacheProxy<Integer, Integer> jcacheLoading() {
    return jcacheLoading;
  }

  public CacheProxy<Integer, Integer> jcache() {
    return jcache;
  }

  public CacheManager cacheManager() {
    return cacheManager;
  }

  public FakeTicker ticker() {
    return ticker;
  }

  public Duration currentTime() {
    return Duration.ofNanos(ticker.read());
  }

  public void advanceHalfExpiry() {
    ticker.advance(EXPIRY_DURATION.dividedBy(2));
  }

  public void advancePastExpiry() {
    ticker.advance(EXPIRY_DURATION.multipliedBy(2));
  }

  public static JCacheStatisticsMXBean getStatistics(CacheProxy<Integer, Integer> cache) {
    return cache.statistics;
  }

  public static @Nullable Expirable<Integer> getExpirable(
      CacheProxy<Integer, Integer> cache, Integer key) {
    return cache.cache.getIfPresent(key);
  }

  /** Returns a configured {@link ConditionFactory} that polls at a short interval. */
  public static ConditionFactory await() {
    return Awaitility.with()
        .pollDelay(ONE_MILLISECOND)
        .pollInterval(ONE_MILLISECOND);
  }

  /** Returns {@code null} for use when testing null checks while satisfying null analysis tools. */
  @SuppressFBWarnings("AI_ANNOTATION_ISSUES_NEEDS_NULLABLE")
  @SuppressWarnings({ "DataFlowIssue", "NullableProblems",
      "NullAway", "TypeParameterUnusedInFormals" })
  public static <T> @NonNull T nullRef() {
    return null;
  }

  @Override
  public void close() {
    cachingProvider.close();
  }

  /** Returns a builder for creating a fixture. */
  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final CaffeineConfiguration<Integer, Integer> base;
    private final FakeTicker ticker;

    private Consumer<CaffeineConfiguration<Integer, Integer>> configurator;
    private Consumer<CaffeineConfiguration<Integer, Integer>> loading;

    private Builder() {
      ticker = new FakeTicker().advance(START_TIME);
      base = new CaffeineConfiguration<Integer, Integer>()
          .setTickerFactory(() -> ticker::read);
      configurator = config -> {};
      loading = this::loading;
    }

    @CanIgnoreReturnValue
    public Builder configure(Consumer<CaffeineConfiguration<Integer, Integer>> configurator) {
      this.configurator = configurator;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder loading(Consumer<CaffeineConfiguration<Integer, Integer>> loading) {
      this.loading = loading;
      return this;
    }

    public JCacheFixture build() {
      return new JCacheFixture(this);
    }

    private void loading(CaffeineConfiguration<Integer, Integer> configuration) {
      configuration.setCacheLoaderFactory(() -> new CacheLoader<>() {
        @Override public Integer load(Integer key) {
          return key;
        }
        @Override public ImmutableMap<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
          return Maps.toMap(ImmutableSet.copyOf(keys), this::load);
        }
      });
      configuration.setReadThrough(true);
    }
  }
}
