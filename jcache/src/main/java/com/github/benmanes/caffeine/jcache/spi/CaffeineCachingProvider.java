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
package com.github.benmanes.caffeine.jcache.spi;

import static javax.cache.configuration.OptionalFeature.STORE_BY_REFERENCE;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

import javax.annotation.concurrent.GuardedBy;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.OptionalFeature;
import javax.cache.spi.CachingProvider;

import com.github.benmanes.caffeine.jcache.CacheManagerImpl;

/**
 * A provider that produces a JCache implementation backed by Caffeine. Typically this provider is
 * instantiated using {@link Caching#getCachingProvider()}, which discovers this implementation
 * through a {@link java.util.ServiceLoader}.
 * <p>
 * This provider is expected to be used for application life cycle events, like initialization. It
 * is not expected that all requests flow through the provider to obtain the cache manager and cache
 * instances for request operations. Internally, this implementation is synchronized to avoid using
 * excess memory due to its infrequent usage.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineCachingProvider implements CachingProvider {
  @GuardedBy("itself")
  private final Map<ClassLoader, Map<URI, CacheManager>> cacheManagers;

  public CaffeineCachingProvider() {
    this.cacheManagers = new WeakHashMap<>(1);
  }

  @Override
  public ClassLoader getDefaultClassLoader() {
    return getClass().getClassLoader();
  }

  @Override
  public URI getDefaultURI() {
    return URI.create(getClass().getName());
  }

  @Override
  public Properties getDefaultProperties() {
    return new Properties();
  }

  @Override
  public CacheManager getCacheManager() {
    return getCacheManager(getDefaultURI(), getDefaultClassLoader());
  }

  @Override
  public CacheManager getCacheManager(URI uri, ClassLoader classLoader) {
    return getCacheManager(uri, classLoader, getDefaultProperties());
  }

  @Override
  public CacheManager getCacheManager(URI uri, ClassLoader classLoader, Properties properties) {
    URI managerURI = getManagerUri(uri);
    ClassLoader managerClassLoader = getManagerClassLoader(classLoader);

    synchronized (cacheManagers) {
      Map<URI, CacheManager> cacheManagersByURI = cacheManagers.computeIfAbsent(
          managerClassLoader, any -> new HashMap<>());
      return cacheManagersByURI.computeIfAbsent(managerURI, any -> {
        Properties managerProperties = (properties == null) ? getDefaultProperties() : properties;
        return new CacheManagerImpl(this, managerURI, managerClassLoader, managerProperties);
      });
    }
  }

  @Override
  public void close() {
    synchronized (cacheManagers) {
      for (ClassLoader classLoader : new ArrayList<>(cacheManagers.keySet())) {
        close(classLoader);
      }
    }
  }

  @Override
  public void close(ClassLoader classLoader) {
    synchronized (cacheManagers) {
      ClassLoader managerClassLoader = getManagerClassLoader(classLoader);
      Map<URI, CacheManager> cacheManagersByURI = cacheManagers.remove(managerClassLoader);
      if (cacheManagersByURI != null) {
        for (CacheManager cacheManager : cacheManagersByURI.values()) {
          cacheManager.close();
        }
      }
    }
  }

  @Override
  public void close(URI uri, ClassLoader classLoader) {
    synchronized (cacheManagers) {
      ClassLoader managerClassLoader = getManagerClassLoader(classLoader);
      Map<URI, CacheManager> cacheManagersByURI = cacheManagers.get(managerClassLoader);

      if (cacheManagersByURI != null) {
        CacheManager cacheManager = cacheManagersByURI.remove(getManagerUri(uri));
        if (cacheManager != null) {
          cacheManager.close();
        }
        if (cacheManagersByURI.isEmpty()) {
          cacheManagers.remove(managerClassLoader);
        }
      }
    }
  }

  @Override
  public boolean isSupported(OptionalFeature optionalFeature) {
    return (optionalFeature == STORE_BY_REFERENCE);
  }

  private URI getManagerUri(URI uri) {
    return (uri == null) ? getDefaultURI() : uri;
  }

  private ClassLoader getManagerClassLoader(ClassLoader classLoader) {
    return (classLoader == null) ? getDefaultClassLoader() : classLoader;
  }
}
