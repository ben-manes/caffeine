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

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.cache.configuration.Factory;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;
import javax.inject.Inject;

import com.github.benmanes.caffeine.jcache.expiry.JCacheExpiryPolicy;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

/**
 * Static utility methods pertaining to externalized {@link CaffeineConfiguration} entries using the
 * Typesafe Config library.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class TypesafeConfigurator {
  static final Logger logger = Logger.getLogger(TypesafeConfigurator.class.getName());

  static FactoryCreator factoryCreator = FactoryBuilder::factoryOf;

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
   * Retrieves the cache's settings from the configuration resource if defined.
   *
   * @param config the configuration resource
   * @param cacheName the name of the cache
   * @param <K> the type of keys maintained the cache
   * @param <V> the type of cached values
   * @return the configuration for the cache
   */
  public static <K, V> Optional<CaffeineConfiguration<K, V>> from(Config config, String cacheName) {
    CaffeineConfiguration<K, V> configuration = null;
    try {
      if (config.hasPath("caffeine.jcache." + cacheName)) {
        configuration = new Configurator<K, V>(config, cacheName).configure();
      }
    } catch (ConfigException.BadPath e) {
      logger.log(Level.WARNING, "Failed to load cache configuration", e);
    }
    return Optional.ofNullable(configuration);
  }

  /**
   * Specifies how {@link Factory} instances are created for a given class name. The default
   * strategy uses {@link Class#newInstance()} and requires the class has a no-args constructor.
   *
   * @param factoryCreator the strategy for creating a factory
   */
  @Inject
  public static void setFactoryBuilder(FactoryCreator factoryCreator) {
    TypesafeConfigurator.factoryCreator = requireNonNull(factoryCreator);
  }

  /** A one-shot builder for creating a configuration instance. */
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

    /** Returns a configuration built from the external settings. */
    CaffeineConfiguration<K, V> configure() {
      addStoreByValue();
      addListeners();
      addReadThrough();
      addWriteThrough();
      addMonitoring();
      addLazyExpiration();
      addEagerExpiration();
      addRefresh();
      addMaximum();

      return configuration;
    }

    /** Adds the store-by-value settings. */
    private void addStoreByValue() {
      boolean enabled = config.getBoolean("store-by-value.enabled");
      configuration.setStoreByValue(enabled);
      if (config.hasPath("store-by-value.strategy")) {
        configuration.setCopierFactory(factoryCreator.factoryOf(
            config.getString("store-by-value.strategy")));
      }
    }

    /** Adds the entry listeners settings. */
    private void addListeners() {
      for (String path : config.getStringList("listeners")) {
        Config listener = rootConfig.getConfig(path);

        Factory<? extends CacheEntryListener<? super K, ? super V>> listenerFactory =
            factoryCreator.factoryOf(listener.getString("class"));
        Factory<? extends CacheEntryEventFilter<? super K, ? super V>> filterFactory = null;
        if (listener.hasPath("filter")) {
          filterFactory = factoryCreator.factoryOf(listener.getString("filter"));
        }
        boolean oldValueRequired = listener.getBoolean("old-value-required");
        boolean synchronous = listener.getBoolean("synchronous");
        configuration.addCacheEntryListenerConfiguration(
            new MutableCacheEntryListenerConfiguration<>(
                listenerFactory, filterFactory, oldValueRequired, synchronous));

      }
    }

    /** Adds the read through settings. */
    private void addReadThrough() {
      boolean isReadThrough = config.getBoolean("read-through.enabled");
      configuration.setReadThrough(isReadThrough);
      if (config.hasPath("read-through.loader")) {
        String loaderClass = config.getString("read-through.loader");
        configuration.setCacheLoaderFactory(factoryCreator.factoryOf(loaderClass));
      }
    }

    /** Adds the write through settings. */
    private void addWriteThrough() {
      boolean isWriteThrough = config.getBoolean("write-through.enabled");
      configuration.setWriteThrough(isWriteThrough);
      if (config.hasPath("write-through.writer")) {
        String writerClass = config.getString("write-through.writer");
        configuration.setCacheWriterFactory(factoryCreator.factoryOf(writerClass));
      }
    }

    /** Adds the JMX monitoring settings. */
    private void addMonitoring() {
      configuration.setStatisticsEnabled(config.getBoolean("monitoring.statistics"));
      configuration.setManagementEnabled(config.getBoolean("monitoring.management"));
    }

    /** Adds the JCache specification's lazy expiration settings. */
    public void addLazyExpiration() {
      Duration creation = getDurationFor("policy.lazy-expiration.creation");
      Duration update = getDurationFor("policy.lazy-expiration.update");
      Duration access = getDurationFor("policy.lazy-expiration.access");

      boolean eternal = Objects.equals(creation, Duration.ETERNAL)
          && Objects.equals(update, Duration.ETERNAL)
          && Objects.equals(access, Duration.ETERNAL);
      Factory<? extends ExpiryPolicy> factory = eternal
          ? EternalExpiryPolicy.factoryOf()
          : FactoryBuilder.factoryOf(new JCacheExpiryPolicy(creation, update, access));
      configuration.setExpiryPolicyFactory(factory);
    }

    /** Returns the duration for the expiration time. */
    private @Nullable Duration getDurationFor(String path) {
      if (!config.hasPath(path)) {
        return null;
      }
      if (config.getString(path).equalsIgnoreCase("eternal")) {
        return Duration.ETERNAL;
      }
      long millis = config.getDuration(path, TimeUnit.MILLISECONDS);
      return new Duration(TimeUnit.MILLISECONDS, millis);
    }

    /** Adds the Caffeine eager expiration settings. */
    public void addEagerExpiration() {
      Config expiration = config.getConfig("policy.eager-expiration");
      if (expiration.hasPath("after-write")) {
        long nanos = expiration.getDuration("after-write", TimeUnit.NANOSECONDS);
        configuration.setExpireAfterWrite(OptionalLong.of(nanos));
      }
      if (expiration.hasPath("after-access")) {
        long nanos = expiration.getDuration("after-access", TimeUnit.NANOSECONDS);
        configuration.setExpireAfterAccess(OptionalLong.of(nanos));
      }
    }

    /** Adds the Caffeine refresh settings. */
    public void addRefresh() {
      Config refresh = config.getConfig("policy.refresh");
      if (refresh.hasPath("after-write")) {
        long nanos = refresh.getDuration("after-write", TimeUnit.NANOSECONDS);
        configuration.setRefreshAfterWrite(OptionalLong.of(nanos));
      }
    }

    /** Adds the maximum size and weight bounding settings. */
    private void addMaximum() {
      Config maximum = config.getConfig("policy.maximum");
      if (maximum.hasPath("size")) {
        configuration.setMaximumSize(OptionalLong.of(maximum.getLong("size")));
      }
      if (maximum.hasPath("weight")) {
        configuration.setMaximumWeight(OptionalLong.of(maximum.getLong("weight")));
      }
      if (maximum.hasPath("weigher")) {
        configuration.setWeigherFactory(FactoryBuilder.factoryOf(maximum.getString("weigher")));
      }
    }
  }
}
