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
import javax.cache.configuration.Factory;
import javax.cache.integration.CacheLoader;
import javax.cache.spi.CachingProvider;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;

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
  private final URI uri;

  private volatile boolean closed;

  public CacheManagerImpl(CachingProvider cacheProvider, URI uri, ClassLoader classLoader,
      Properties properties) {
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
      CompleteConfiguration<K, V> config = copyFrom(configuration);
      Factory<CacheLoader<K, V>> cacheLoaderFactory = config.getCacheLoaderFactory();
      CacheLoader<K, V> cacheLoader =
          (cacheLoaderFactory == null) ? null : cacheLoaderFactory.create();

      Caffeine<Object, Object> builder = Caffeine.newBuilder();
      // TODO(ben): configure...

      if (config.isReadThrough() && (cacheLoader != null)) {
        return new LoadingCacheProxy<K, V>(cacheName, this, config,
            builder.build(new CacheLoaderAdapter<>(cacheLoader)), cacheLoader);
      } else {
        return new CacheProxy<K, V>(cacheName, this, config,
            builder.build(), Optional.ofNullable(cacheLoader));
      }
    });

    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) cache;
    return castedCache;
  }

  @Override
  public <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
    requireNonNull(keyType);
    requireNonNull(valueType);

    Cache<K, V> cache = getCache(cacheName);
    if (cache == null) {
      return null;
    }

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
    requireNonNull(cacheName);
    requireNotClosed();

    Cache<?, ?> cache = caches.computeIfAbsent(cacheName, key -> /* load from config */ null);

    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) cache;
    return castedCache;
  }

  @Override
  public Iterable<String> getCacheNames() {
    return Collections.unmodifiableCollection(caches.keySet());
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

  private void requireNotClosed() {
    if (isClosed()) {
      throw new IllegalStateException();
    }
  }

  private static <K, V> CompleteConfiguration<K, V> copyFrom(Configuration<K, V> configuration) {
    if (configuration instanceof CompleteConfiguration<?, ?>) {
      return new CaffeineConfiguration<>((CompleteConfiguration<K, V>) configuration);
    }
    return new CaffeineConfiguration<K, V>()
        .setStoreByValue(configuration.isStoreByValue())
        .setTypes(configuration.getKeyType(), configuration.getValueType());
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
      return delegate.load(key);
    }

    @Override
    public Map<K, V> loadAll(Iterable<? extends K> keys) {
      return delegate.loadAll(keys);
    }
  }
}
