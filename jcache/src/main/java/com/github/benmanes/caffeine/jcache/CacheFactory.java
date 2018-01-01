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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.cache.CacheManager;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.Factory;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.event.JCacheEvictionListener;
import com.github.benmanes.caffeine.jcache.integration.JCacheLoaderAdapter;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.typesafe.config.Config;

/**
 * A factory for creating a cache from the configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheFactory {
  final Config rootConfig;
  final CacheManager cacheManager;

  public CacheFactory(CacheManager cacheManager, Config rootConfig) {
    this.cacheManager = requireNonNull(cacheManager);
    this.rootConfig = requireNonNull(rootConfig);
  }

  /**
   * Returns a if the cache definition is found in the external settings file.
   *
   * @param cacheName the name of the cache
   * @return {@code true} if a definition exists
   */
  public boolean isDefinedExternally(String cacheName) {
    return TypesafeConfigurator.cacheNames(rootConfig).contains(cacheName);
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
    CaffeineConfiguration<K, V> config = resolveConfigurationFor(configuration);
    return new Builder<>(cacheName, config).build();
  }

  /** Copies the configuration and overlays it on top of the default settings. */
  @SuppressWarnings("PMD.AccessorMethodGeneration")
  private <K, V> CaffeineConfiguration<K, V> resolveConfigurationFor(
      Configuration<K, V> configuration) {
    if (configuration instanceof CaffeineConfiguration<?, ?>) {
      return new CaffeineConfiguration<>((CaffeineConfiguration<K, V>) configuration);
    }

    CaffeineConfiguration<K, V> template = TypesafeConfigurator.defaults(rootConfig);
    if (configuration instanceof CompleteConfiguration<?, ?>) {
      CompleteConfiguration<K, V> complete = (CompleteConfiguration<K, V>) configuration;
      template.setReadThrough(complete.isReadThrough());
      template.setWriteThrough(complete.isWriteThrough());
      template.setManagementEnabled(complete.isManagementEnabled());
      template.setStatisticsEnabled(complete.isStatisticsEnabled());
      template.getCacheEntryListenerConfigurations()
          .forEach(template::removeCacheEntryListenerConfiguration);
      complete.getCacheEntryListenerConfigurations()
          .forEach(template::addCacheEntryListenerConfiguration);
      template.setCacheLoaderFactory(complete.getCacheLoaderFactory());
      template.setCacheWriterFactory(complete.getCacheWriterFactory());
      template.setExpiryPolicyFactory(complete.getExpiryPolicyFactory());
    }

    template.setTypes(configuration.getKeyType(), configuration.getValueType());
    template.setStoreByValue(configuration.isStoreByValue());
    return template;
  }

  /** A one-shot builder for creating a cache instance. */
  private final class Builder<K, V> {
    final Ticker ticker;
    final String cacheName;
    final Executor executor;
    final ExpiryPolicy expiryPolicy;
    final EventDispatcher<K, V> dispatcher;
    final JCacheStatisticsMXBean statistics;
    final Caffeine<Object, Object> caffeine;
    final CaffeineConfiguration<K, V> config;

    Builder(String cacheName, CaffeineConfiguration<K, V> config) {
      this.config = config;
      this.cacheName = cacheName;
      this.caffeine = Caffeine.newBuilder();
      this.statistics = new JCacheStatisticsMXBean();
      this.ticker = config.getTickerFactory().create();
      this.executor = config.getExecutorFactory().create();
      this.expiryPolicy = config.getExpiryPolicyFactory().create();
      this.dispatcher = new EventDispatcher<>(executor);

      caffeine.executor(executor);
      config.getCacheEntryListenerConfigurations().forEach(dispatcher::register);
    }

    /** Creates a configured cache. */
    public CacheProxy<K, V> build() {
      boolean evicts = false;
      evicts |= configureMaximumSize();
      evicts |= configureMaximumWeight();
      evicts |= configureExpireAfterWrite();
      evicts |= configureExpireAfterAccess();
      evicts |= configureExpireVariably();

      JCacheEvictionListener<K, V> evictionListener = null;
      if (evicts) {
        evictionListener = new JCacheEvictionListener<>(dispatcher, statistics);
        caffeine.writer(evictionListener);
      }

      CacheProxy<K, V> cache;
      if (isReadThrough()) {
        configureRefreshAfterWrite();
        cache = newLoadingCacheProxy();
      } else {
        cache = newCacheProxy();
      }

      if (evictionListener != null) {
        evictionListener.setCache(cache);
      }
      return cache;
    }

    /** Determines if the cache should operate in read through mode. */
    private boolean isReadThrough() {
      return config.isReadThrough() && (config.getCacheLoaderFactory() != null);
    }

    /** Creates a cache that does not read through on a cache miss. */
    private CacheProxy<K, V> newCacheProxy() {
      Optional<CacheLoader<K, V>> cacheLoader =
          Optional.ofNullable(config.getCacheLoaderFactory()).map(Factory::create);
      return new CacheProxy<>(cacheName, executor, cacheManager, config, caffeine.build(),
          dispatcher, cacheLoader, expiryPolicy, ticker, statistics);
    }

    /** Creates a cache that reads through on a cache miss. */
    private CacheProxy<K, V> newLoadingCacheProxy() {
      CacheLoader<K, V> cacheLoader = config.getCacheLoaderFactory().create();
      JCacheLoaderAdapter<K, V> adapter = new JCacheLoaderAdapter<>(
          cacheLoader, dispatcher, expiryPolicy, ticker, statistics);
      CacheProxy<K, V> cache = new LoadingCacheProxy<>(cacheName, executor, cacheManager, config,
          caffeine.build(adapter), dispatcher, cacheLoader, expiryPolicy, ticker, statistics);
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
        Weigher<K, V> weigher = config.getWeigherFactory().map(Factory::create)
            .orElseThrow(() -> new IllegalStateException("Weigher not configured"));
        caffeine.weigher((K key, Expirable<V> expirable) -> {
          return weigher.weigh(key, expirable.get());
        });
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

    /** Configures the write expiration and returns if set. */
    private boolean configureExpireVariably() {
      config.getExpiryFactory().ifPresent(factory -> {
        Expiry<K, V> expiry = factory.create();
        caffeine.expireAfter(new Expiry<K, Expirable<V>>() {
          @Override public long expireAfterCreate(K key, Expirable<V> expirable, long currentTime) {
            return expiry.expireAfterCreate(key, expirable.get(), currentTime);
          }
          @Override public long expireAfterUpdate(K key, Expirable<V> expirable,
              long currentTime, long currentDuration) {
            return expiry.expireAfterUpdate(key, expirable.get(), currentTime, currentDuration);
          }
          @Override public long expireAfterRead(K key, Expirable<V> expirable,
              long currentTime, long currentDuration) {
            return expiry.expireAfterRead(key, expirable.get(), currentTime, currentDuration);
          }
        });
      });
      return config.getExpireAfterWrite().isPresent();
    }

    private void configureRefreshAfterWrite() {
      if (config.getRefreshAfterWrite().isPresent()) {
        caffeine.refreshAfterWrite(config.getRefreshAfterWrite().getAsLong(), TimeUnit.NANOSECONDS);
      }
    }
  }
}
