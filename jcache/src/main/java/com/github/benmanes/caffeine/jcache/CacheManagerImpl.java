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
import java.util.stream.Collectors;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.spi.CachingProvider;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
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
  private final Map<String, Cache<?, ?>> caches;
  private final CachingProvider cacheProvider;
  private final Properties properties;
  private final Config preconfigured;
  private final URI uri;

  private volatile boolean closed;

  public CacheManagerImpl(CachingProvider cacheProvider, URI uri, ClassLoader classLoader,
      Properties properties) {
    this.classLoaderReference = new WeakReference<>(requireNonNull(classLoader));
    this.preconfigured = ConfigFactory.load().getConfig("caffeine.jcache");
    this.cacheProvider = requireNonNull(cacheProvider);
    this.properties = requireNonNull(properties);
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

    Cache<?, ?> cache = caches.compute(cacheName, (name, existing) -> {
      if ((existing != null) && !existing.isClosed()) {
        throw new CacheException("Cache " + cacheName + " already exists");
      }
      return newCache(cacheName, configuration);
    });

    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) cache;
    return castedCache;
  }

  private <K, V> Cache<K, V> newCache(String cacheName, Configuration<K, V> configuration) {
    CaffeineConfiguration<K, V> config = resolveConfigurationFor(cacheName, configuration);
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    configure(builder, config);

    CacheLoader<K, V> cacheLoader = null;
    if (config.getCacheLoaderFactory() != null) {
      cacheLoader = config.getCacheLoaderFactory().create();
    }

    if (config.isReadThrough() && (cacheLoader != null)) {
      return new LoadingCacheProxy<K, V>(cacheName, this, config,
          builder.build(new CacheLoaderAdapter<>(cacheLoader)), cacheLoader);
    } else {
      return new CacheProxy<K, V>(cacheName, this, config,
          builder.build(), Optional.ofNullable(cacheLoader));
    }
  }

  private static <K, V> void configure(Caffeine<Object, Object> builder,
      CaffeineConfiguration<K, V> config) {
    // TODO(ben): configure...
  }

  @Override
  public <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
    Cache<K, V> cache = getOrCreateCache(cacheName);
    if (cache == null) {
      return null;
    }
    requireNonNull(keyType);
    requireNonNull(valueType);

    @SuppressWarnings("unchecked")
    Configuration<?, ?> config = cache.getConfiguration(Configuration.class);
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
    Cache<K, V> cache = getOrCreateCache(cacheName);
    if (cache == null) {
      return null;
    }

    @SuppressWarnings("unchecked")
    Configuration<?, ?> configuration = cache.getConfiguration(Configuration.class);
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
  private <K, V> Cache<K, V> getOrCreateCache(String cacheName) {
    requireNonNull(cacheName);
    requireNotClosed();

    Cache<?, ?> cache = caches.get(cacheName);
    if (cache == null) {
      try {
        if (preconfigured.hasPath(cacheName)) {
          CaffeineConfiguration<K, V> configuration = CaffeineConfiguration.from(
              preconfigured.getConfig(cacheName).withFallback(preconfigured));
          cache = caches.computeIfAbsent(cacheName, name -> newCache(cacheName, configuration));
        }
      } catch (ConfigException.BadPath e) {}
    }
    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) cache;
    return castedCache;
  }

  @Override
  public Iterable<String> getCacheNames() {
    return Collections.unmodifiableCollection(new ArrayList<>(caches.keySet()));
  }

  @Override
  public void destroyCache(String cacheName) {
    requireNonNull(cacheName);
    requireNotClosed();

    Cache<?, ?> cache = caches.remove(cacheName);
    if (cache != null) {
      cache.close();
    }
  }

  @Override
  public void enableManagement(String cacheName, boolean enabled) {
    requireNonNull(cacheName);
    requireNotClosed();

    Cache<?, ?> cache = caches.get(cacheName);
    if (cache != null) {
      // TODO(ben): implement
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public void enableStatistics(String cacheName, boolean enabled) {
    requireNonNull(cacheName);
    requireNotClosed();

    Cache<?, ?> cache = caches.get(cacheName);
    if (cache != null) {
      // TODO(ben): implement
    }
    throw new UnsupportedOperationException();
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

    CaffeineConfiguration<K, V> defaults = CaffeineConfiguration.from(
        preconfigured.withFallback(preconfigured));
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

  /** An adapter from a JCache cache loader to Caffeine's. */
  private static final class CacheLoaderAdapter<K, V>
      implements com.github.benmanes.caffeine.cache.CacheLoader<K, V> {
    private final CacheLoader<K, V> delegate;

    CacheLoaderAdapter(CacheLoader<K, V> delegate) {
      this.delegate = delegate;
    }

    @Override
    public V load(K key) {
      try {
        return delegate.load(key);
      } catch (CacheLoaderException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new CacheLoaderException(e);
      }
    }

    @Override
    public Map<K, V> loadAll(Iterable<? extends K> keys) {
      try {
        return delegate.loadAll(keys).entrySet().stream()
            .filter(entry -> (entry.getKey() != null) && (entry.getValue() != null))
            .collect(Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue()));
      } catch (CacheLoaderException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new CacheLoaderException(e);
      }
    }
  }
}
