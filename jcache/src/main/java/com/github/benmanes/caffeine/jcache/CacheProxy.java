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

import java.util.HashMap;
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
import com.github.benmanes.caffeine.jcache.processor.EntryProcessorEntry;


/**
 * An implementation of JSR-107 {@link Cache} backed by a Caffeine cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class CacheProxy<K, V> implements Cache<K, V> {
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

  /**
   * Returns a copy of the object if value-based caching is enabled.
   *
   * @param object the object to be copied
   * @param <T> the type of object being copied
   * @return a copy of the object if storing by value or the same instance if by reference
   */
  protected @Nullable <T> T copyOf(@Nullable T object) {
    return (object == null) ? null : copyStrategy.copy(object, cacheManager.getClassLoader());
  }

  @Override
  public V get(K key) {
    requireNotClosed();
    try {
      return copyOf(cache.getIfPresent(key));
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    }
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    requireNotClosed();
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
    requireNotClosed();
    return cache.asMap().containsKey(key);
  }

  @Override
  public void loadAll(Set<? extends K> keys, boolean replaceExistingValues,
      CompletionListener completionListener) {
    requireNotClosed();
    for (K key : keys) {
      requireNonNull(key);
    }

    if (!cacheLoader.isPresent()) {
      return;
    }
    ForkJoinPool.commonPool().execute(() -> {
      try {
        if (replaceExistingValues) {
          cache.putAll(cacheLoader.get().loadAll(keys));
          completionListener.onCompletion();
          return;
        }

        List<K> keysToLoad = keys.stream()
            .filter(key -> !cache.asMap().containsKey(key))
            .collect(Collectors.<K>toList());
        Map<K, V> result = cacheLoader.get().loadAll(keysToLoad);
        for (Map.Entry<K, V> entry : result.entrySet()) {
          cache.asMap().putIfAbsent(entry.getKey(), entry.getValue());
        }
        completionListener.onCompletion();
      } catch (Exception e) {
        completionListener.onException(e);
      }
    });
  }

  @Override
  public void put(K key, V value) {
    requireNotClosed();
    cache.put(copyOf(key), copyOf(value));
  }

  @Override
  public V getAndPut(K key, V value) {
    requireNotClosed();
    return copyOf(cache.asMap().put(copyOf(key), copyOf(value)));
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    requireNotClosed();
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      requireNonNull(entry.getKey());
      requireNonNull(entry.getValue());
    }
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public boolean putIfAbsent(K key, V value) {
    requireNotClosed();
    requireNonNull(value);

    boolean[] absent = { false };
    cache.get(copyOf(key), k -> {
      absent[0] = true;
      return copyOf(value);
    });
    return absent[0];
  }

  @Override
  public boolean remove(K key) {
    requireNotClosed();
    return cache.asMap().remove(key) != null;
  }

  @Override
  public boolean remove(K key, V oldValue) {
    requireNotClosed();
    requireNonNull(oldValue);
    return cache.asMap().remove(key, oldValue);
  }

  @Override
  public V getAndRemove(K key) {
    requireNotClosed();
    return copyOf(cache.asMap().remove(key));
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNotClosed();
    return cache.asMap().replace(key, oldValue, copyOf(newValue));
  }

  @Override
  public boolean replace(K key, V value) {
    requireNotClosed();
    return cache.asMap().replace(key, copyOf(value)) != null;
  }

  @Override
  public V getAndReplace(K key, V value) {
    requireNotClosed();
    return copyOf(cache.asMap().replace(key, copyOf(value)));
  }

  @Override
  public void removeAll(Set<? extends K> keys) {
    requireNotClosed();
    cache.invalidateAll(keys);
  }

  @Override
  public void removeAll() {
    requireNotClosed();
    clear();
  }

  @Override
  public void clear() {
    requireNotClosed();
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
    requireNonNull(entryProcessor);
    requireNonNull(arguments);
    requireNotClosed();

    Object[] result = new Object[1];

    cache.asMap().compute(copyOf(key), (k, value) -> {
      if ((value == null) && cacheLoader.isPresent() && configuration.isReadThrough()) {
        try {
          value = cacheLoader().get().load(key);
        } catch (RuntimeException ignored) {}
      }
      EntryProcessorEntry<K, V> entry = new EntryProcessorEntry<>(key, value, (value != null));
      try {
        result[0] = entryProcessor.process(entry, arguments);
        return entry.getValue();
      } catch (EntryProcessorException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EntryProcessorException(e);
      }
    });

    @SuppressWarnings("unchecked")
    T castedResult = (T) result[0];
    return castedResult;
  }

  @Override
  public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys,
      EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
    Map<K, EntryProcessorResult<T>> results = new HashMap<>(keys.size());
    for (K key : keys) {
      try {
        T result = invoke(key, entryProcessor, arguments);
        if (result != null) {
          results.put(key, () -> result);
        }
      } catch (EntryProcessorException e) {
        results.put(key, () -> { throw e; });
      }
    }
    return results;
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
    cache.invalidateAll();
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
    requireNotClosed();

    return new Iterator<Cache.Entry<K, V>>() {
      final Iterator<Map.Entry<K, V>> delegate = cache.asMap().entrySet().iterator();

      @Override
      public boolean hasNext() {
        return delegate.hasNext();
      }

      @Override
      public Cache.Entry<K, V> next() {
        Map.Entry<K, V> entry = delegate.next();
        return new EntryProxy<K, V>(copyOf(entry.getKey()), copyOf(entry.getValue()));
      }

      @Override
      public void remove() {
        delegate.remove();
      }
    };
  }

  /** Checks that the cache is not closed. */
  protected final void requireNotClosed() {
    if (isClosed()) {
      throw new IllegalStateException();
    }
  }
}
