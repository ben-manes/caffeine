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
package com.github.benmanes.caffeine.jcache;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.cache.CacheManager;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.integration.JCacheLoaderAdapter;
import com.github.benmanes.caffeine.jcache.integration.JCacheRemovalListener;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.typesafe.config.Config;

/**
 * A factory for creating a cache from the configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheFactory {
  private final Config rootConfig;
  private final CacheManager cacheManager;

  public CacheFactory(CacheManager cacheManager, Config rootConfig) {
    this.cacheManager = requireNonNull(cacheManager);
    this.rootConfig = requireNonNull(rootConfig);
  }

  /**
   * Returns a newly created cache instance if a definition is found in the external settings file.
   *
   * @param cacheName the name of the cache
   * @return a new cache instance or null if the named cache is not defined in the settings file
   */
  public @Nullable <K, V> CacheProxy<K, V> tryToCreateFromExternalSettings(String cacheName) {
    Optional<CaffeineConfiguration<K, V>> configuration =
        TypesafeConfigurator.from(rootConfig, cacheName);
    return configuration.isPresent() ? createCache(cacheName, configuration.get()) : null;
  }

  /**
   * Returns a fully constructed cache based on the cache
   *
   * @param cacheName the name of the cache
   * @param configuration the full cache definition
   * @return a newly constructed cache instance
   */
  public <K, V> CacheProxy<K, V> createCache(String cacheName, Configuration<K, V> configuration) {
    CaffeineConfiguration<K, V> config = resolveConfigurationFor(cacheName, configuration);
    return new Builder<>(cacheName, config).build();
  }

  /** Copies the configuration and overlays it on top of the default settings. */
  private <K, V> CaffeineConfiguration<K, V> resolveConfigurationFor(
      String cacheName, Configuration<K, V> configuration) {
    if (configuration instanceof CaffeineConfiguration<?, ?>) {
      return new CaffeineConfiguration<K, V>((CaffeineConfiguration<K, V>) configuration);
    }

    CaffeineConfiguration<K, V> defaults = TypesafeConfigurator.defaults(rootConfig);
    if (configuration instanceof CompleteConfiguration<?, ?>) {
      CaffeineConfiguration<K, V> config = new CaffeineConfiguration<>(
          (CompleteConfiguration<K, V>) configuration);
      config.setCopyStrategyFactory(defaults.getCopyStrategyFactory());
      return config;
    }

    defaults.setTypes(configuration.getKeyType(), configuration.getValueType());
    defaults.setStoreByValue(configuration.isStoreByValue());
    return defaults;
  }

  /** A one-shot builder for creating a cache instance. */
  private final class Builder<K, V> {
    final Ticker ticker;
    final String cacheName;
    final ExpiryPolicy expiry;
    final EventDispatcher<K, V> dispatcher;
    final JCacheStatisticsMXBean statistics;
    final Caffeine<Object, Object> caffeine;
    final CaffeineConfiguration<K, V> config;

    CacheLoader<K, V> cacheLoader;
    JCacheRemovalListener<K, V> removalListener;

    Builder(String cacheName, CaffeineConfiguration<K, V> config) {
      this.config = config;
      this.cacheName = cacheName;
      this.ticker = Ticker.systemTicker();
      this.caffeine = Caffeine.newBuilder();
      this.statistics = new JCacheStatisticsMXBean();
      this.expiry = config.getExpiryPolicyFactory().create();
      this.dispatcher = new EventDispatcher<>(ForkJoinPool.commonPool());

      caffeine.name(cacheName::toString);
      if (config.getCacheLoaderFactory() != null) {
        cacheLoader = config.getCacheLoaderFactory().create();
      }
      config.getCacheEntryListenerConfigurations().forEach(dispatcher::register);
    }

    /** Creates a configured cache. */
    public CacheProxy<K, V> build() {
      boolean requiresRemovalListener =
          configureMaximumSize() ||
          configureMaximumWeight() ||
          configureExpireAfterWrite() ||
          configureExpireAfterAccess();
      if (requiresRemovalListener) {
        configureRemovalListener();
      }

      CacheProxy<K, V> cache = isReadThrough() ? newLoadingCacheProxy() : newCacheProxy();
      if (requiresRemovalListener) {
        removalListener.setCache(cache);
      }
      return cache;
    }

    /** Determines if the cache should operate in read through mode. */
    private boolean isReadThrough() {
      return config.isReadThrough() && (cacheLoader != null);
    }

    /** Creates a cache that does not read through on a cache miss. */
    private CacheProxy<K, V> newCacheProxy() {
      return new CacheProxy<K, V>(cacheName, cacheManager, config, caffeine.build(),
          dispatcher, Optional.ofNullable(cacheLoader), expiry, ticker, statistics);
    }

    /** Creates a cache that reads through on a cache miss. */
    private CacheProxy<K, V> newLoadingCacheProxy() {
      JCacheLoaderAdapter<K, V> adapter = new JCacheLoaderAdapter<>(
          cacheLoader, dispatcher, expiry, ticker, statistics);
      CacheProxy<K, V> cache = new LoadingCacheProxy<K, V>(cacheName, cacheManager, config,
          caffeine.build(adapter), dispatcher, cacheLoader, expiry, ticker, statistics);
      adapter.setCache(cache);
      return cache;
    }

    /** Configures the maximum size and returns if set. */
    private boolean configureMaximumSize() {
      if (config.getMaximumSize().isPresent()) {
        caffeine.maximumSize(config.getMaximumSize().getAsLong());
      }
      return config.getMaximumSize().isPresent();
    }

    /** Configures the maximum weight and returns if set. */
    private boolean configureMaximumWeight() {
      if (config.getMaximumWeight().isPresent()) {
        caffeine.maximumWeight(config.getMaximumWeight().getAsLong());
        caffeine.weigher(config.getWeigherFactory().create());
      }
      return config.getMaximumWeight().isPresent();
    }

    /** Configures the write expiration and returns if set. */
    private boolean configureExpireAfterWrite() {
      if (config.getExpireAfterWrite().isPresent()) {
        caffeine.expireAfterWrite(config.getExpireAfterWrite().getAsLong(), TimeUnit.NANOSECONDS);
      }
      return config.getExpireAfterWrite().isPresent();
    }

    /** Configures the access expiration and returns if set. */
    private boolean configureExpireAfterAccess() {
      if (config.getExpireAfterAccess().isPresent()) {
        caffeine.expireAfterAccess(config.getExpireAfterAccess().getAsLong(), TimeUnit.NANOSECONDS);
      }
      return config.getExpireAfterAccess().isPresent();
    }

    /** Configures the removal listener. */
    private void configureRemovalListener() {
      removalListener = new JCacheRemovalListener<>(dispatcher, statistics);
      caffeine.removalListener(removalListener);
    }
  }
}
