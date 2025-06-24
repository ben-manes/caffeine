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

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.OptionalFeature;
import javax.cache.spi.CachingProvider;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import com.github.benmanes.caffeine.jcache.CacheManagerImpl;
import com.google.errorprone.annotations.Var;
import com.google.errorprone.annotations.concurrent.GuardedBy;

/**
 * A provider that produces a JCache implementation backed by Caffeine. Typically, this provider is
 * instantiated using {@link Caching#getCachingProvider()} which discovers this implementation
 * through a {@link java.util.ServiceLoader}.
 * <p>
 * This provider is expected to be used for application life cycle events, like initialization. It
 * is not expected that all requests flow through the provider to obtain the cache manager and cache
 * instances for request operations. Internally, this implementation is synchronized to avoid using
 * excess memory due to its infrequent usage.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Component
@NullMarked
public final class CaffeineCachingProvider implements CachingProvider {
  private static final ClassLoader DEFAULT_CLASS_LOADER =
      new JCacheClassLoader(Thread.currentThread().getContextClassLoader());

  @GuardedBy("itself")
  final Map<ClassLoader, Map<URI, CacheManager>> cacheManagers;

  boolean isOsgiComponent;

  public CaffeineCachingProvider() {
    this.cacheManagers = new WeakHashMap<>(1);
  }

  @Override
  public ClassLoader getDefaultClassLoader() {
    return DEFAULT_CLASS_LOADER;
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
    URI managerUri = getManagerUri(uri);
    ClassLoader managerClassLoader = getManagerClassLoader(classLoader);

    synchronized (cacheManagers) {
      Map<URI, CacheManager> cacheManagersByUri = cacheManagers.computeIfAbsent(
          managerClassLoader, any -> new HashMap<>());
      return cacheManagersByUri.computeIfAbsent(managerUri, any -> {
        Properties managerProperties = (properties == null) ? getDefaultProperties() : properties;
        return new CacheManagerImpl(this, isOsgiComponent,
            managerUri, managerClassLoader, managerProperties);
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
  @SuppressWarnings("PMD.CloseResource")
  public void close(ClassLoader classLoader) {
    synchronized (cacheManagers) {
      ClassLoader managerClassLoader = getManagerClassLoader(classLoader);
      Map<URI, CacheManager> cacheManagersByUri = cacheManagers.remove(managerClassLoader);
      if (cacheManagersByUri != null) {
        for (CacheManager cacheManager : cacheManagersByUri.values()) {
          cacheManager.close();
        }
      }
    }
  }

  @Override
  @SuppressWarnings("PMD.CloseResource")
  public void close(URI uri, ClassLoader classLoader) {
    synchronized (cacheManagers) {
      ClassLoader managerClassLoader = getManagerClassLoader(classLoader);
      Map<URI, CacheManager> cacheManagersByUri = cacheManagers.get(managerClassLoader);

      if (cacheManagersByUri != null) {
        CacheManager cacheManager = cacheManagersByUri.remove(getManagerUri(uri));
        if (cacheManager != null) {
          cacheManager.close();
        }
        if (cacheManagersByUri.isEmpty()) {
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

  /**
   * A {@link ClassLoader} that combines {@code Thread.currentThread().getContextClassLoader()}
   * and {@code getClass().getClassLoader()}.
   */
  static class JCacheClassLoader extends ClassLoader {

    public JCacheClassLoader(@Nullable ClassLoader parent) {
      super(parent);
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
      @Var ClassNotFoundException error = null;

      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      if ((contextClassLoader != null) && (contextClassLoader != DEFAULT_CLASS_LOADER)) {
        try {
          return contextClassLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
          error = e;
        }
      }

      ClassLoader classClassLoader = getClassClassLoader();
      if ((classClassLoader != null) && (classClassLoader != contextClassLoader)) {
        try {
          return classClassLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
          error = e;
        }
      }

      ClassLoader parentClassLoader = getParent();
      if ((parentClassLoader != null)
          && (parentClassLoader != classClassLoader)
          && (parentClassLoader != contextClassLoader)) {
        try {
          return parentClassLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
          error = e;
        }
      }
      throw (error == null) ? new ClassNotFoundException(name) : error;
    }

    @Override
    public @Nullable URL getResource(String name) {
      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      if ((contextClassLoader != null) && (contextClassLoader != DEFAULT_CLASS_LOADER)) {
        URL resource = contextClassLoader.getResource(name);
        if (resource != null) {
          return resource;
        }
      }

      ClassLoader classClassLoader = getClassClassLoader();
      if ((classClassLoader != null) && (classClassLoader != contextClassLoader)) {
        URL resource = classClassLoader.getResource(name);
        if (resource != null) {
          return resource;
        }
      }

      ClassLoader parentClassLoader = getParent();
      if ((parentClassLoader != null)
          && (parentClassLoader != classClassLoader)
          && (parentClassLoader != contextClassLoader)) {
        URL resource = parentClassLoader.getResource(name);
        if (resource != null) {
          return resource;
        }
      }

      return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      var resources = new ArrayList<URL>();

      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      if ((contextClassLoader != null) && contextClassLoader != DEFAULT_CLASS_LOADER) {
        resources.addAll(Collections.list(contextClassLoader.getResources(name)));
      }

      ClassLoader classClassLoader = getClassClassLoader();
      if ((classClassLoader != null) && (classClassLoader != contextClassLoader)) {
        resources.addAll(Collections.list(classClassLoader.getResources(name)));
      }

      ClassLoader parentClassLoader = getParent();
      if ((parentClassLoader != null)
          && (parentClassLoader != classClassLoader)
          && (parentClassLoader != contextClassLoader)) {
        resources.addAll(Collections.list(parentClassLoader.getResources(name)));
      }

      return Collections.enumeration(resources);
    }

    @Nullable ClassLoader getClassClassLoader() {
      return getClass().getClassLoader();
    }
  }

  @Activate
  @SuppressWarnings("unused")
  private void activate() {
    isOsgiComponent = true;
  }
}
