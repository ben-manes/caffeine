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

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.copy.CopyStrategy;

/**
 * An implementation of JSR-107 {@link Cache} backed by a Caffeine cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
class CacheProxy<K, V> implements Cache<K, V> {
  private final com.github.benmanes.caffeine.cache.Cache<K, V> cache;
  private final CaffeineConfiguration<K, V> configuration;
  private final Optional<CacheLoader<K, V>> cacheLoader;
  private final CopyStrategy copyStrategy;
  private final CacheManager cacheManager;
  private final String name;

  private volatile boolean closed;

  CacheProxy(String name, CacheManager cacheManager, CaffeineConfiguration<K, V> configuration,
      com.github.benmanes.caffeine.cache.Cache<K, V> cache,
      Optional<CacheLoader<K, V>> cacheLoader) {
    this.configuration = requireNonNull(configuration);
    this.cacheManager = requireNonNull(cacheManager);
    this.cacheLoader = requireNonNull(cacheLoader);
    this.cache = requireNonNull(cache);
    this.name = requireNonNull(name);

    copyStrategy = configuration.isStoreByValue()
        ? configuration.getCopyStrategyFactory().create()
        : CacheProxy::identity;
  }

  private static <T> T identity(T object, ClassLoader classLoader) {
    return object;
  }

  protected Optional<CacheLoader<K, V>> cacheLoader() {
    return cacheLoader;
  }

  /** Returns a copy of the object if value-based caching is enabled. */
  protected @Nullable <T> T copyOf(@Nullable T object) {
    return (object == null) ? null : copyStrategy.copy(object, cacheManager.getClassLoader());
  }

  @Override
  public V get(K key) {
    V value = cache.getIfPresent(key);
    return copyOf(value);
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    try {
      return copyOf(cache.getAllPresent(keys));
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    }
  }

  @Override
  public boolean containsKey(K key) {
    return cache.asMap().containsKey(key);
  }

  @Override
  public void loadAll(Set<? extends K> keys, boolean replaceExistingValues,
      CompletionListener completionListener) {
    if (!cacheLoader.isPresent()) {
      return;
    }
    ForkJoinPool.commonPool().execute(() -> {
      if (replaceExistingValues) {
        cache.putAll(cacheLoader.get().loadAll(keys));
        return;
      }

      List<K> keysToLoad = keys.stream()
          .filter(key -> !cache.asMap().containsKey(key))
          .collect(Collectors.<K>toList());
      Map<K, V> result = cacheLoader.get().loadAll(keysToLoad);
      for (Map.Entry<K, V> entry : result.entrySet()) {
        cache.asMap().putIfAbsent(entry.getKey(), entry.getValue());
      }
    });
  }

  @Override
  public void put(K key, V value) {
    cache.put(copyOf(key), copyOf(value));
  }

  @Override
  public V getAndPut(K key, V value) {
    return copyOf(cache.asMap().put(copyOf(key), copyOf(value)));
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public boolean putIfAbsent(K key, V value) {
    boolean[] absent = { false };
    cache.asMap().computeIfAbsent(copyOf(key), k -> {
      absent[0] = true;
      return copyOf(value);
    });
    return absent[0];
  }

  @Override
  public boolean remove(K key) {
    return cache.asMap().remove(key) != null;
  }

  @Override
  public boolean remove(K key, V oldValue) {
    return cache.asMap().remove(key, oldValue);
  }

  @Override
  public V getAndRemove(K key) {
    return copyOf(cache.asMap().remove(key));
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    return cache.asMap().replace(key, oldValue, copyOf(newValue));
  }

  @Override
  public boolean replace(K key, V value) {
    return cache.asMap().replace(key, copyOf(value)) != null;
  }

  @Override
  public V getAndReplace(K key, V value) {
    return copyOf(cache.asMap().replace(key, copyOf(value)));
  }

  @Override
  public void removeAll(Set<? extends K> keys) {
    cache.invalidateAll(keys);
  }

  @Override
  public void removeAll() {
    clear();
  }

  @Override
  public void clear() {
    cache.invalidateAll();
  }

  @Override
  public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
    if (clazz.isInstance(configuration)) {
      return clazz.cast(configuration);
    }
    throw new IllegalArgumentException("The configuration class " + clazz
        + " is not supported by this implementation");
  }

  @Override
  public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... arguments)
      throws EntryProcessorException {
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys,
      EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public CacheManager getCacheManager() {
    return cacheManager;
  }

  @Override
  public void close() {
    if (isClosed()) {
      return;
    }
    synchronized (cache) {
      if (!isClosed()) {
        cacheManager.destroyCache(name);
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
    throw new IllegalArgumentException("Unwrapping to " + clazz
        + " is not supported by this implementation");
  }

  @Override
  public void registerCacheEntryListener(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void deregisterCacheEntryListener(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterator<Cache.Entry<K, V>> iterator() {
    if (isClosed()) {
      throw new IllegalStateException();
    }
    return new Iterator<Cache.Entry<K, V>>() {
      final Iterator<Map.Entry<K, V>> delegate = cache.asMap().entrySet().iterator();

      @Override
      public boolean hasNext() {
        return delegate.hasNext();
      }

      @Override
      public Cache.Entry<K, V> next() {
        Map.Entry<K, V> entry = delegate.next();
        return new EntryView<K, V>(copyOf(entry.getKey()), copyOf(entry.getValue()));
      }

      @Override
      public void remove() {
        delegate.remove();
      }
    };
  }

  private static final class EntryView<K, V> extends SimpleImmutableEntry<K, V>
      implements Cache.Entry<K, V> {
    private static final long serialVersionUID = 1L;

    EntryView(K key, V value) {
      super(key, value);
    }

    @Override
    public <T> T unwrap(Class<T> clazz) {
      if (!clazz.isInstance(this)) {
        throw new IllegalArgumentException("Class " + clazz + " is unknown to this implementation");
      }
      @SuppressWarnings("unchecked")
      T castedEntry = (T) this;
      return castedEntry;
    }
  }
}
