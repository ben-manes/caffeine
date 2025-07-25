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

import java.io.File;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.cache.CacheManager;
import javax.cache.configuration.Factory;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.cache.expiry.ExpiryPolicy;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.jcache.expiry.JCacheExpiryPolicy;
import com.google.errorprone.annotations.Var;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;

import jakarta.inject.Inject;

/**
 * Static utility methods pertaining to externalized {@link CaffeineConfiguration} entries using the
 * Typesafe Config library.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NullMarked
public final class TypesafeConfigurator {
  static final Logger logger = System.getLogger(TypesafeConfigurator.class.getName());

  static final AtomicReference<ConfigSource> configSource =
      new AtomicReference<>(TypesafeConfigurator::resolveConfig);
  static final AtomicReference<FactoryCreator> factoryCreator =
      new AtomicReference<>(FactoryBuilder::factoryOf);

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
    try {
      requireNonNull(cacheName);
      return config.hasPath("caffeine.jcache." + cacheName)
          ? Optional.of(new Configurator<K, V>(config, cacheName).configure())
          : Optional.empty();
    } catch (ConfigException.BadPath e) {
      logger.log(Level.WARNING, "Failed to load cache configuration", e);
      return Optional.empty();
    }
  }

  /**
   * Specifies how {@link Factory} instances are created for a given class name. The default
   * strategy uses {@link Class#newInstance()} and requires the class has a no-args constructor.
   *
   * @param factoryCreator the strategy for creating a factory
   */
  @Inject
  @SuppressWarnings({"deprecation", "UnnecessarilyVisible"})
  public static void setFactoryCreator(FactoryCreator factoryCreator) {
    TypesafeConfigurator.factoryCreator.set(requireNonNull(factoryCreator));
  }

  /** Returns the strategy for how factory instances are created. */
  public static FactoryCreator factoryCreator() {
    return requireNonNull(factoryCreator.get());
  }

  /**
   * Specifies how the {@link Config} instance should be loaded. The default strategy uses the uri
   * provided by {@link CacheManager#getURI()} as an optional override location to parse from a
   * file system or classpath resource, or else returns {@link ConfigFactory#load(ClassLoader)}.
   * The configuration is retrieved on-demand, allowing for it to be reloaded, and it is assumed
   * that the source caches it as needed.
   *
   * @param configSource the strategy for loading the configuration
   */
  public static void setConfigSource(Supplier<Config> configSource) {
    requireNonNull(configSource);
    setConfigSource((uri, classloader) -> configSource.get());
  }

  /**
   * Specifies how the {@link Config} instance should be loaded. The default strategy uses the uri
   * provided by {@link CacheManager#getURI()} as an optional override location to parse from a
   * file system or classpath resource, or else returns {@link ConfigFactory#load(ClassLoader)}.
   * The configuration is retrieved on-demand, allowing for it to be reloaded, and it is assumed
   * that the source caches it as needed.
   *
   * @param configSource the strategy for loading the configuration from a URI
   */
  public static void setConfigSource(ConfigSource configSource) {
    TypesafeConfigurator.configSource.set(requireNonNull(configSource));
  }

  /** Returns the strategy for loading the configuration. */
  public static ConfigSource configSource() {
    return requireNonNull(configSource.get());
  }

  /** Returns the configuration by applying the default strategy. */
  private static Config resolveConfig(URI uri, ClassLoader classloader) {
    requireNonNull(uri);
    requireNonNull(classloader);
    var options = ConfigParseOptions.defaults()
        .setClassLoader(classloader)
        .setAllowMissing(false);
    if ((uri.getScheme() != null) && uri.getScheme().equalsIgnoreCase("file")) {
      return ConfigFactory.defaultOverrides(classloader)
          .withFallback(ConfigFactory.parseFile(new File(uri), options))
          .withFallback(ConfigFactory.defaultReferenceUnresolved(classloader));
    } else if ((uri.getScheme() != null) && uri.getScheme().equalsIgnoreCase("jar")) {
      try {
        return ConfigFactory.defaultOverrides(classloader)
            .withFallback(ConfigFactory.parseURL(uri.toURL(), options))
            .withFallback(ConfigFactory.defaultReferenceUnresolved(classloader));
      } catch (MalformedURLException e) {
        throw new ConfigException.BadPath(uri.toString(), "Failed to load cache configuration", e);
      }
    } else if (isResource(uri)) {
      return ConfigFactory.defaultOverrides(classloader)
          .withFallback(ConfigFactory.parseResources(uri.getSchemeSpecificPart(), options))
          .withFallback(ConfigFactory.defaultReferenceUnresolved(classloader));
    }
    return ConfigFactory.load(classloader);
  }

  /** Returns if the uri is a file or classpath resource. */
  private static boolean isResource(URI uri) {
    if ((uri.getScheme() != null) && !uri.getScheme().equalsIgnoreCase("classpath")) {
      return false;
    }
    var path = uri.getSchemeSpecificPart();
    int dotIndex = path.lastIndexOf('.');
    if (dotIndex != -1) {
      var extension = path.substring(dotIndex + 1);
      for (var format : ConfigSyntax.values()) {
        if (format.toString().equalsIgnoreCase(extension)) {
          return true;
        }
      }
    }
    return false;
  }

  /** A one-shot builder for creating a configuration instance. */
  static final class Configurator<K, V> {
    final CaffeineConfiguration<K, V> configuration;
    final Config customized;
    final Config merged;
    final Config root;

    Configurator(Config config, String cacheName) {
      this.root = requireNonNull(config);
      this.configuration = new CaffeineConfiguration<>();
      this.customized = root.getConfig("caffeine.jcache." + requireNonNull(cacheName));
      this.merged = customized.withFallback(root.getConfig("caffeine.jcache.default"));
    }

    /** Returns a configuration built from the external settings. */
    CaffeineConfiguration<K, V> configure() {
      addKeyValueTypes();
      addStoreByValue();
      addExecutor();
      addScheduler();
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
        var keyType = (Class<K>) Class.forName(merged.getString("key-type"));
        @SuppressWarnings("unchecked")
        var valueType = (Class<V>) Class.forName(merged.getString("value-type"));
        configuration.setTypes(keyType, valueType);
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(e);
      }
    }

    /** Adds the store-by-value settings. */
    private void addStoreByValue() {
      configuration.setStoreByValue(merged.getBoolean("store-by-value.enabled"));
      if (isSet("store-by-value.strategy")) {
        configuration.setCopierFactory(factoryCreator().factoryOf(
            merged.getString("store-by-value.strategy")));
      }
    }

    /** Adds the executor settings. */
    public void addExecutor() {
      if (isSet("executor")) {
        configuration.setExecutorFactory(factoryCreator()
            .factoryOf(merged.getString("executor")));
      }
    }

    /** Adds the scheduler settings. */
    public void addScheduler() {
      if (isSet("scheduler")) {
        configuration.setSchedulerFactory(factoryCreator()
            .factoryOf(merged.getString("scheduler")));
      }
    }

    /** Adds the entry listeners settings. */
    private void addListeners() {
      for (String path : merged.getStringList("listeners")) {
        Config listener = root.getConfig(path);

        Factory<? extends CacheEntryListener<? super K, ? super V>> listenerFactory =
            factoryCreator().factoryOf(listener.getString("class"));
        @Var Factory<? extends CacheEntryEventFilter<? super K, ? super V>> filterFactory = null;
        if (listener.hasPath("filter")) {
          filterFactory = factoryCreator().factoryOf(listener.getString("filter"));
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
        configuration.setCacheLoaderFactory(factoryCreator().factoryOf(
            merged.getString("read-through.loader")));
      }
    }

    /** Adds the write-through settings. */
    private void addWriteThrough() {
      configuration.setWriteThrough(merged.getBoolean("write-through.enabled"));
      if (isSet("write-through.writer")) {
        configuration.setCacheWriterFactory(factoryCreator().factoryOf(
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
