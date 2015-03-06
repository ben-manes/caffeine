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

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.cache.configuration.Factory;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import com.github.benmanes.caffeine.jcache.expiry.JCacheExpiryPolicy;
import com.typesafe.config.Config;

/**
 * Static utility methods pertaining to externalized {@link CaffeineConfiguration} entries using the
 * Typesafe Config library.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TypesafeConfigurator {

  private TypesafeConfigurator() {}

  /**
   * Retrieves the default cache settings from the configuration resource.
   *
   * @param config the configuration resource
   * @param <K> the type of keys maintained the cache
   * @param <V> the type of cached values
   * @return the default configuration for a cache
   */
  public static <K, V> CaffeineConfiguration<K, V> defaults(Config config) {
    return new Configurator<K, V>(config, "default").configure();
  }

  /**
   * Retrieves the cache's settings from the configuration resource.
   *
   * @param config the configuration resource
   * @param cacheName the name of the cache
   * @param <K> the type of keys maintained the cache
   * @param <V> the type of cached values
   * @return the configuration for the cache
   */
  public static <K, V> Optional<CaffeineConfiguration<K, V>> from(Config config, String cacheName) {
    return config.hasPath("caffeine.jcache." + cacheName)
        ? Optional.of(new Configurator<K, V>(config, cacheName).configure())
        : Optional.empty();
  }

  private static final class Configurator<K, V> {
    final CaffeineConfiguration<K, V> configuration;
    final Config rootConfig;
    final Config config;

    Configurator(Config config, String cacheName) {
      this.rootConfig = requireNonNull(config);
      this.configuration = new CaffeineConfiguration<>();
      this.config = rootConfig.getConfig("caffeine.jcache." + cacheName)
          .withFallback(rootConfig.getConfig("caffeine.jcache.default"));
    }

    CaffeineConfiguration<K, V> configure() {
      addStoreByValue();
      addListeners();
      addReadThrough();
      addWriteThrough();
      addMonitoring();
      addExpiration();
      addMaximumSize();

      return configuration;
    }

    private void addStoreByValue() {
      boolean enabled = config.getBoolean("store-by-value.enabled");
      configuration.setStatisticsEnabled(enabled);
      if (config.hasPath("store-by-value.strategy")) {
        configuration.setCopyStrategyFactory(FactoryBuilder.factoryOf(
            config.getString("store-by-value.strategy")));
      }
    }

    private void addListeners() {
      for (String path : config.getStringList("listeners")) {
        Config listener = rootConfig.getConfig(path);

        Factory<? extends CacheEntryListener<? super K, ? super V>> listenerFactory =
            FactoryBuilder.factoryOf(listener.getString("class"));
        Factory<? extends CacheEntryEventFilter<? super K, ? super V>> filterFactory = null;
        if (listener.hasPath("filter")) {
          filterFactory = FactoryBuilder.factoryOf(listener.getString("filter"));
        }
        boolean oldValueRequired = listener.getBoolean("old-value-required");
        boolean synchronous = listener.getBoolean("synchronous");
        configuration.addCacheEntryListenerConfiguration(
            new MutableCacheEntryListenerConfiguration<>(
                listenerFactory, filterFactory, oldValueRequired, synchronous));

      }
    }

    private void addReadThrough() {
      boolean isReadThrough = config.getBoolean("read-through.enabled");
      configuration.setReadThrough(isReadThrough);
      if (config.hasPath("read-through.loader")) {
        String loaderClass = config.getString("read-through.loader");
        configuration.setCacheLoaderFactory(FactoryBuilder.factoryOf(loaderClass));
      }
    }

    private void addWriteThrough() {
      boolean isWriteThrough = config.getBoolean("write-through.enabled");
      configuration.setWriteThrough(isWriteThrough);
      if (config.hasPath("write-through.writer")) {
        String writerClass = config.getString("write-through.writer");
        configuration.setCacheWriterFactory(FactoryBuilder.factoryOf(writerClass));
      }
    }

    private void addMonitoring() {
      configuration.setStatisticsEnabled(config.getBoolean("monitoring.statistics"));
      configuration.setManagementEnabled(config.getBoolean("monitoring.management"));
    }

    public void addExpiration() {
      Duration creation = getDurationFor("policy.expiration.creation");
      Duration update = getDurationFor("policy.expiration.update");
      Duration access = getDurationFor("policy.expiration.access");

      boolean eternal = creation.isEternal() && update.isEternal() && access.isEternal();
      Factory<? extends ExpiryPolicy> factory = eternal
          ? EternalExpiryPolicy.factoryOf()
          : FactoryBuilder.factoryOf(new JCacheExpiryPolicy(creation, update, access));
      configuration.setExpiryPolicyFactory(factory);
    }

    private Duration getDurationFor(String path) {
      if (config.hasPath(path)) {
        long nanos = config.getDuration(path, TimeUnit.NANOSECONDS);
        return new Duration(TimeUnit.NANOSECONDS, nanos);
      }
      return Duration.ETERNAL;
    }

    private void addMaximumSize() {
      if (config.hasPath("policy.maximum-size")) {
        configuration.setMaximimWeight(config.getLong("policy.maximum-size"));
      }
      if (config.hasPath("policy.maximum-weight")) {
        configuration.setMaximimWeight(config.getLong("policy.maximum-weight"));
      }
      if (config.hasPath("policy.weigher")) {
        configuration.setWeigherFactory(FactoryBuilder.factoryOf("policy.weigher"));
      }
    }
  }
}
