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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.cache.configuration.Factory;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;
import javax.inject.Inject;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.jcache.expiry.JCacheExpiryPolicy;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

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
  static Supplier<Config> configSource = ConfigFactory::load;

  private TypesafeConfigurator() {}

  /**
   * Retrieves the names of the caches defined in the configuration resource.
   *
   * @param config the configuration resource
   * @return the names of the configured caches
   */
  public static Set<String> cacheNames(Config config) {
    return config.hasPath("caffeine.jcache")
        ? Collections.unmodifiableSet(config.getObject("caffeine.jcache").keySet())
        : Collections.emptySet();
  }

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
  public static void setFactoryCreator(FactoryCreator factoryCreator) {
    TypesafeConfigurator.factoryCreator = requireNonNull(factoryCreator);
  }

  /**
   * Specifies how the {@link Config} instance should be loaded. The default strategy uses
   * {@link ConfigFactory#load()}. The configuration is retrieved on-demand, allowing for it to be
   * reloaded, and it is assumed that the source caches it as needed.
   *
   * @param configSource the strategy for loading the configuration
   */
  public static void setConfigSource(Supplier<Config> configSource) {
    TypesafeConfigurator.configSource = requireNonNull(configSource);
  }

  /** Returns the strategy for loading the configuration. */
  public static Supplier<Config> configSource() {
    return TypesafeConfigurator.configSource;
  }

  /** A one-shot builder for creating a configuration instance. */
  private static final class Configurator<K, V> {
    final CaffeineConfiguration<K, V> configuration;
    final Config customized;
    final Config merged;
    final Config root;

    Configurator(Config config, String cacheName) {
      this.root = requireNonNull(config);
      this.configuration = new CaffeineConfiguration<>();
      this.customized = root.getConfig("caffeine.jcache." + cacheName);
      this.merged = customized.withFallback(root.getConfig("caffeine.jcache.default"));
    }

    /** Returns a configuration built from the external settings. */
    CaffeineConfiguration<K, V> configure() {
      addKeyValueTypes();
      addStoreByValue();
      addExecutor();
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

    /** Adds the key and value class types. */
    private void addKeyValueTypes() {
      try {
        @SuppressWarnings("unchecked")
        Class<K> keyType = (Class<K>) Class.forName(merged.getString("key-type"));
        @SuppressWarnings("unchecked")
        Class<V> valueType = (Class<V>) Class.forName(merged.getString("value-type"));
        configuration.setTypes(keyType, valueType);
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }

    /** Adds the store-by-value settings. */
    private void addStoreByValue() {
      configuration.setStoreByValue(merged.getBoolean("store-by-value.enabled"));
      if (isSet("store-by-value.strategy")) {
        configuration.setCopierFactory(factoryCreator.factoryOf(
            merged.getString("store-by-value.strategy")));
      }
    }

    /** Adds the executor settings. */
    public void addExecutor() {
      if (isSet("executor")) {
        configuration.setExecutorFactory(factoryCreator.factoryOf(merged.getString("executor")));
      }
    }

    /** Adds the entry listeners settings. */
    private void addListeners() {
      for (String path : merged.getStringList("listeners")) {
        Config listener = root.getConfig(path);

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
      configuration.setReadThrough(merged.getBoolean("read-through.enabled"));
      if (isSet("read-through.loader")) {
        configuration.setCacheLoaderFactory(factoryCreator.factoryOf(
            merged.getString("read-through.loader")));
      }
    }

    /** Adds the write through settings. */
    private void addWriteThrough() {
      configuration.setWriteThrough(merged.getBoolean("write-through.enabled"));
      if (isSet("write-through.writer")) {
        configuration.setCacheWriterFactory(factoryCreator.factoryOf(
            merged.getString("write-through.writer")));
      }
    }

    /** Adds the monitoring settings. */
    private void addMonitoring() {
      configuration.setNativeStatisticsEnabled(merged.getBoolean("monitoring.native-statistics"));
      configuration.setStatisticsEnabled(merged.getBoolean("monitoring.statistics"));
      configuration.setManagementEnabled(merged.getBoolean("monitoring.management"));
    }

    /** Adds the JCache specification's lazy expiration settings. */
    public void addLazyExpiration() {
      Duration creation = getDurationFor("policy.lazy-expiration.creation");
      Duration update = getDurationFor("policy.lazy-expiration.update");
      Duration access = getDurationFor("policy.lazy-expiration.access");
      requireNonNull(creation, "policy.lazy-expiration.creation may not be null");

      boolean eternal = Objects.equals(creation, Duration.ETERNAL)
          && Objects.equals(update, Duration.ETERNAL)
          && Objects.equals(access, Duration.ETERNAL);
      @SuppressWarnings("NullAway")
      Factory<? extends ExpiryPolicy> factory = eternal
          ? EternalExpiryPolicy.factoryOf()
          : FactoryBuilder.factoryOf(new JCacheExpiryPolicy(creation, update, access));
      configuration.setExpiryPolicyFactory(factory);
    }

    /** Returns the duration for the expiration time. */
    private @Nullable Duration getDurationFor(String path) {
      if (!isSet(path)) {
        return null;
      }
      if (merged.getString(path).equalsIgnoreCase("eternal")) {
        return Duration.ETERNAL;
      }
      long millis = merged.getDuration(path, MILLISECONDS);
      return new Duration(MILLISECONDS, millis);
    }

    /** Adds the Caffeine eager expiration settings. */
    public void addEagerExpiration() {
      if (isSet("policy.eager-expiration.after-write")) {
        long nanos = merged.getDuration("policy.eager-expiration.after-write", NANOSECONDS);
        configuration.setExpireAfterWrite(OptionalLong.of(nanos));
      }
      if (isSet("policy.eager-expiration.after-access")) {
        long nanos = merged.getDuration("policy.eager-expiration.after-access", NANOSECONDS);
        configuration.setExpireAfterAccess(OptionalLong.of(nanos));
      }
      if (isSet("policy.eager-expiration.variable")) {
        configuration.setExpiryFactory(Optional.of(FactoryBuilder.factoryOf(
            merged.getString("policy.eager-expiration.variable"))));
      }
    }

    /** Adds the Caffeine refresh settings. */
    public void addRefresh() {
      if (isSet("policy.refresh.after-write")) {
        long nanos = merged.getDuration("policy.refresh.after-write", NANOSECONDS);
        configuration.setRefreshAfterWrite(OptionalLong.of(nanos));
      }
    }

    /** Adds the maximum size and weight bounding settings. */
    private void addMaximum() {
      if (isSet("policy.maximum.size")) {
        configuration.setMaximumSize(OptionalLong.of(merged.getLong("policy.maximum.size")));
      }
      if (isSet("policy.maximum.weight")) {
        configuration.setMaximumWeight(OptionalLong.of(merged.getLong("policy.maximum.weight")));
      }
      if (isSet("policy.maximum.weigher")) {
        configuration.setWeigherFactory(Optional.of(
            FactoryBuilder.factoryOf(merged.getString("policy.maximum.weigher"))));
      }
    }

    /** Returns if the value is present (not unset by the cache configuration). */
    private boolean isSet(String path) {
      if (!merged.hasPath(path)) {
        return false;
      } else if (customized.hasPathOrNull(path)) {
        return !customized.getIsNull(path);
      }
      return true;
    }
  }
}
