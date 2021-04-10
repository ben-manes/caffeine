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
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.Configuration;
import javax.cache.spi.CachingProvider;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;

/**
 * An implementation of JSR-107 {@link CacheManager} that manages Caffeine-based caches.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.CloseResource")
public final class CacheManagerImpl implements CacheManager {
  private final WeakReference<ClassLoader> classLoaderReference;
  private final Map<String, CacheProxy<?, ?>> caches;
  private final CachingProvider cacheProvider;
  private final Properties properties;
  private final URI uri;
  private final boolean runsAsAnOsgiBundle;

  private volatile boolean closed;

  public CacheManagerImpl(CachingProvider cacheProvider,
      URI uri, ClassLoader classLoader, Properties properties) {
    this.runsAsAnOsgiBundle = (cacheProvider instanceof CaffeineCachingProvider)
        && ((CaffeineCachingProvider) cacheProvider).isOsgiComponent();
    this.classLoaderReference = new WeakReference<>(requireNonNull(classLoader));
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
  public @Nullable ClassLoader getClassLoader() {
    return classLoaderReference.get();
  }

  @Override
  public Properties getProperties() {
    return properties;
  }

  @Override
  public <K, V, C extends Configuration<K, V>> Cache<K, V> createCache(
      String cacheName, C configuration) {
    ClassLoader old = Thread.currentThread().getContextClassLoader();
    try {
      if (runsAsAnOsgiBundle) {
        // override the context class loader with the CachingManager's classloader
        Thread.currentThread().setContextClassLoader(getClassLoader());
      }
      requireNotClosed();
      requireNonNull(configuration);

      CacheProxy<?, ?> cache = caches.compute(cacheName, (name, existing) -> {
        if ((existing != null) && !existing.isClosed()) {
          throw new CacheException("Cache " + cacheName + " already exists");
        } else if (CacheFactory.isDefinedExternally(cacheName)) {
          throw new CacheException("Cache " + cacheName + " is configured externally");
        }
        return CacheFactory.createCache(this, cacheName, configuration);
      });
      enableManagement(cache.getName(), cache.getConfiguration().isManagementEnabled());
      enableStatistics(cache.getName(), cache.getConfiguration().isStatisticsEnabled());

      @SuppressWarnings("unchecked")
      Cache<K, V> castedCache = (Cache<K, V>) cache;
      return castedCache;
    } finally {
      Thread.currentThread().setContextClassLoader(old);
    }
  }

  @Override
  public @Nullable <K, V> Cache<K, V> getCache(
      String cacheName, Class<K> keyType, Class<V> valueType) {
    ClassLoader old = Thread.currentThread().getContextClassLoader();
    try {
      if (runsAsAnOsgiBundle) {
        // override the context class loader with the CachingManager's classloader
        Thread.currentThread().setContextClassLoader(getClassLoader());
      }
      CacheProxy<K, V> cache = getCache(cacheName);
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
    } finally {
      Thread.currentThread().setContextClassLoader(old);
    }
  }

  @Override
  public <K, V> CacheProxy<K, V> getCache(String cacheName) {
    ClassLoader old = Thread.currentThread().getContextClassLoader();
    try {
      if (runsAsAnOsgiBundle) {
        // override the context class loader with the CachingManager's classloader
        Thread.currentThread().setContextClassLoader(getClassLoader());
      }
      requireNonNull(cacheName);
      requireNotClosed();

      CacheProxy<?, ?> cache = caches.computeIfAbsent(cacheName, name -> {
        CacheProxy<?, ?> created = CacheFactory.tryToCreateFromExternalSettings(this, name);
        if (created != null) {
          created.enableManagement(created.getConfiguration().isManagementEnabled());
          created.enableStatistics(created.getConfiguration().isStatisticsEnabled());
        }
        return created;
      });

      @SuppressWarnings("unchecked")
      CacheProxy<K, V> castedCache = (CacheProxy<K, V>) cache;
      return castedCache;
    } finally {
      Thread.currentThread().setContextClassLoader(old);
    }
  }

  @Override
  public Iterable<String> getCacheNames() {
    requireNotClosed();
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
    synchronized (this) {
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
}
