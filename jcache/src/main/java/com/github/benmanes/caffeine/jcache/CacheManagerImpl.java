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

import java.lang.ref.WeakReference;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.integration.CacheLoader;
import javax.cache.spi.CachingProvider;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.integration.JCacheLoaderAdapter;
import com.github.benmanes.caffeine.jcache.integration.JCacheRemovalListener;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

/**
 * An implementation of JSR-107 {@link CacheManager} that manages Caffeine-based caches.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheManagerImpl implements CacheManager {
  private final WeakReference<ClassLoader> classLoaderReference;
  private final Map<String, CacheProxy<?, ?>> caches;
  private final CachingProvider cacheProvider;
  private final Properties properties;
  private final Config rootConfig;
  private final URI uri;

  private volatile boolean closed;

  public CacheManagerImpl(CachingProvider cacheProvider, URI uri, ClassLoader classLoader,
      Properties properties) {
    this(cacheProvider, uri, classLoader, properties, ConfigFactory.load());
  }

  public CacheManagerImpl(CachingProvider cacheProvider, URI uri, ClassLoader classLoader,
      Properties properties, Config rootConfig) {
    this.classLoaderReference = new WeakReference<>(requireNonNull(classLoader));
    this.cacheProvider = requireNonNull(cacheProvider);
    this.properties = requireNonNull(properties);
    this.rootConfig = requireNonNull(rootConfig);
    this.caches = new ConcurrentHashMap<>();
    this.uri = requireNonNull(uri);
  }

  @Override
  public CachingProvider getCachingProvider() {
    return cacheProvider;
  }

  @Override
  public URI getURI() {
    return uri;
  }

  @Override
  public ClassLoader getClassLoader() {
    return classLoaderReference.get();
  }

  @Override
  public Properties getProperties() {
    return properties;
  }

  @Override
  public <K, V, C extends Configuration<K, V>> Cache<K, V> createCache(String cacheName,
      C configuration) throws IllegalArgumentException {
    requireNotClosed();
    requireNonNull(configuration);

    CacheProxy<?, ?> cache = caches.compute(cacheName, (name, existing) -> {
      if ((existing != null) && !existing.isClosed()) {
        throw new CacheException("Cache " + cacheName + " already exists");
      }
      return newCache(cacheName, configuration);
    });
    enableManagement(cache.getName(), cache.getConfiguration().isManagementEnabled());
    enableStatistics(cache.getName(), cache.getConfiguration().isStatisticsEnabled());

    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) cache;
    return castedCache;
  }

  private <K, V> CacheProxy<K, V> newCache(String cacheName, Configuration<K, V> configuration) {
    CaffeineConfiguration<K, V> config = resolveConfigurationFor(cacheName, configuration);
    Caffeine<Object, Object> builder = Caffeine.newBuilder();

    EventDispatcher<K, V> dispatcher = new EventDispatcher<K, V>();
    config.getCacheEntryListenerConfigurations().forEach(dispatcher::register);

    Optional<JCacheRemovalListener<K, V>> removalListener = configure(builder, config, dispatcher);

    CacheLoader<K, V> cacheLoader = null;
    if (config.getCacheLoaderFactory() != null) {
      cacheLoader = config.getCacheLoaderFactory().create();
    }

    CacheProxy<K, V> cache;
    if (config.isReadThrough() && (cacheLoader != null)) {
      JCacheLoaderAdapter<K, V> adapter = new JCacheLoaderAdapter<>(cacheLoader, dispatcher);
      cache = new LoadingCacheProxy<K, V>(cacheName, this, config,
          builder.build(adapter), dispatcher, cacheLoader, Ticker.systemTicker());
      adapter.setCache(cache);
    } else {
      cache = new CacheProxy<K, V>(cacheName, this, config, builder.build(),
          dispatcher, Optional.ofNullable(cacheLoader), Ticker.systemTicker());
    }

    if (removalListener.isPresent()) {
      removalListener.get().setCache(cache);
    }

    return cache;
  }

  // TODO(ben): configure...
  private static <K, V> Optional<JCacheRemovalListener<K, V>> configure(
      Caffeine<Object, Object> builder, CaffeineConfiguration<K, V> config,
      EventDispatcher<K, V> dispatcher) {
    boolean requiresRemovalListener = false;

    if (requiresRemovalListener) {
      JCacheRemovalListener<K, V> removalListener = new JCacheRemovalListener<>(dispatcher);
      builder.removalListener(removalListener);
      return Optional.of(removalListener);
    }
    return Optional.empty();
  }

  @Override
  public <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
    CacheProxy<K, V> cache = getOrCreateCache(cacheName);
    if (cache == null) {
      return null;
    }
    requireNonNull(keyType);
    requireNonNull(valueType);

    Configuration<?, ?> config = cache.getConfiguration();
    if (keyType != config.getKeyType()) {
      throw new ClassCastException("Incompatible cache key types specified, expected " +
          config.getKeyType() + " but " + keyType + " was specified");
    } else if (valueType != config.getValueType()) {
      throw new ClassCastException("Incompatible cache value types specified, expected " +
          config.getValueType() + " but " + valueType + " was specified");
    }
    return cache;
  }

  @Override
  public <K, V> Cache<K, V> getCache(String cacheName) {
    CacheProxy<K, V> cache = getOrCreateCache(cacheName);
    if (cache == null) {
      return null;
    }

    Configuration<?, ?> configuration = cache.getConfiguration();
    if (!Object.class.equals(configuration.getKeyType()) ||
        !Object.class.equals(configuration.getValueType())) {
      String msg = String.format("Cache %s was defined with specific types Cache<%s, %s> in which "
          + "case CacheManager.getCache(String, Class, Class) must be used", cacheName,
          configuration.getKeyType(), configuration.getValueType());
      throw new IllegalArgumentException(msg);
    }

    return cache;
  }

  /** Returns the cache, creating it if necessary. */
  private <K, V> CacheProxy<K, V> getOrCreateCache(String cacheName) {
    requireNonNull(cacheName);
    requireNotClosed();

    CacheProxy<?, ?> cache = caches.get(cacheName);
    if (cache == null) {
      try {
        Optional<CaffeineConfiguration<K, V>> configuration =
            TypesafeConfigurator.from(rootConfig, cacheName);
        if (configuration.isPresent()) {
          cache = caches.computeIfAbsent(cacheName, name ->
              newCache(cacheName, configuration.get()));
        }
      } catch (ConfigException.BadPath e) {}
    }
    @SuppressWarnings("unchecked")
    CacheProxy<K, V> castedCache = (CacheProxy<K, V>) cache;
    return castedCache;
  }

  @Override
  public Iterable<String> getCacheNames() {
    return Collections.unmodifiableCollection(new ArrayList<>(caches.keySet()));
  }

  @Override
  public void destroyCache(String cacheName) {
    requireNotClosed();

    Cache<?, ?> cache = caches.remove(cacheName);
    if (cache != null) {
      cache.close();
    }
  }

  @Override
  public void enableManagement(String cacheName, boolean enabled) {
    requireNotClosed();

    CacheProxy<?, ?> cache = caches.get(cacheName);
    if (cache == null) {
      return;
    }
    cache.enableManagement(enabled);
  }

  @Override
  public void enableStatistics(String cacheName, boolean enabled) {
    requireNotClosed();

    CacheProxy<?, ?> cache = caches.get(cacheName);
    if (cache == null) {
      return;
    }
    cache.enableStatistics(enabled);
  }

  @Override
  public void close() {
    if (isClosed()) {
      return;
    }
    synchronized (caches) {
      if (!isClosed()) {
        cacheProvider.close(uri, classLoaderReference.get());
        for (Cache<?, ?> cache : caches.values()) {
          cache.close();
        }
        closed = true;
      }
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public <T> T unwrap(Class<T> clazz) {
    if (clazz.isAssignableFrom(getClass())) {
      return clazz.cast(this);
    }
    throw new IllegalArgumentException("Unwapping to " + clazz
        + " is not a supported by this implementation");
  }

  /** Checks that the cache manager is not closed. */
  private void requireNotClosed() {
    if (isClosed()) {
      throw new IllegalStateException();
    }
  }

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
}
