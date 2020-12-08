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
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.OptionalFeature;
import javax.cache.spi.CachingProvider;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import com.github.benmanes.caffeine.jcache.CacheManagerImpl;
import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.typesafe.config.ConfigFactory;

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
@Component
public final class CaffeineCachingProvider implements CachingProvider {
  private static final ClassLoader DEFAULT_CLASS_LOADER = AccessController.doPrivileged(
      (PrivilegedAction<ClassLoader>) JCacheClassLoader::new);

  @GuardedBy("itself")
  private final Map<ClassLoader, Map<URI, CacheManager>> cacheManagers;

  private boolean isOsgiComponent;

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
  @SuppressWarnings("PMD.CloseResource")
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
  @SuppressWarnings("PMD.CloseResource")
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

  /**
   * A {@link ClassLoader} that combines {@code Thread.currentThread().getContextClassLoader()}
   * and {@code getClass().getClassLoader()}.
   */
  private static final class JCacheClassLoader extends ClassLoader {

    public JCacheClassLoader() {
      super(Thread.currentThread().getContextClassLoader());
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      ClassNotFoundException error = null;
      if ((contextClassLoader != null) && (contextClassLoader != DEFAULT_CLASS_LOADER)) {
        try {
          return contextClassLoader.loadClass(name);
        } catch (ClassNotFoundException e) {
          error = e;
        }
      }

      ClassLoader classClassLoader = getClass().getClassLoader();
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
        return parentClassLoader.loadClass(name);
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

      ClassLoader classClassLoader = getClass().getClassLoader();
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
        return parentClassLoader.getResource(name);
      }

      return null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      List<URL> resources = new ArrayList<>();

      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      if (contextClassLoader != null && contextClassLoader != DEFAULT_CLASS_LOADER) {
        resources.addAll(Collections.list(contextClassLoader.getResources(name)));
      }

      ClassLoader classClassLoader = getClass().getClassLoader();
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
  }

  @Activate
  @SuppressWarnings("unused")
  private void activate() {
    isOsgiComponent = true;
    TypesafeConfigurator.setConfigSource(() -> ConfigFactory.load(DEFAULT_CLASS_LOADER));
  }

  public boolean isOsgiComponent() {
    return isOsgiComponent;
  }

}
