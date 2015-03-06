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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;
import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.copy.CopyStrategy;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.integration.DisabledCacheWriter;
import com.github.benmanes.caffeine.jcache.management.JCacheMXBean;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.github.benmanes.caffeine.jcache.management.JmxRegistration;
import com.github.benmanes.caffeine.jcache.management.JmxRegistration.MBeanType;
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
  private final JCacheStatisticsMXBean statistics;
  private final EventDispatcher<K, V> dispatcher;
  private final CopyStrategy copyStrategy;
  private final CacheManager cacheManager;
  private final CacheWriter<K, V> writer;
  private final JCacheMXBean cacheMXBean;
  private final String name;

  private volatile boolean closed;

  CacheProxy(String name, CacheManager cacheManager, CaffeineConfiguration<K, V> configuration,
      com.github.benmanes.caffeine.cache.Cache<K, V> cache, EventDispatcher<K, V> dispatcher,
      Optional<CacheLoader<K, V>> cacheLoader) {
    this.configuration = requireNonNull(configuration);
    this.cacheManager = requireNonNull(cacheManager);
    this.cacheLoader = requireNonNull(cacheLoader);
    this.dispatcher = requireNonNull(dispatcher);
    this.cache = requireNonNull(cache);
    this.name = requireNonNull(name);

    copyStrategy = configuration.isStoreByValue()
        ? configuration.getCopyStrategyFactory().create()
        : CacheProxy::identity;
    writer = configuration.hasCacheWriter()
        ? configuration.getCacheWriter()
        : DisabledCacheWriter.get();
    cacheMXBean = new JCacheMXBean(this);
    statistics = new JCacheStatisticsMXBean();
  }

  private static <T> T identity(T object, ClassLoader classLoader) {
    return object;
  }

  protected CacheWriter<? super K, ? super V> cacheWriter() {
    return writer;
  }

  protected CaffeineConfiguration<K, V> getConfiguration() {
    return configuration;
  }

  protected Optional<CacheLoader<K, V>> cacheLoader() {
    return cacheLoader;
  }

  protected EventDispatcher<K, V> dispatcher() {
    return dispatcher;
  }

  public JCacheStatisticsMXBean getStatisticsMXBean() {
    return statistics;
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
      V value = copyOf(cache.getIfPresent(key));
      if (value == null) {
        statistics.recordMisses(1L);
      } else {
        statistics.recordHits(1L);
      }
      return value;
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
      Map<K, V> result = copyOf(cache.getAllPresent(keys));
      statistics.recordMisses(keys.size() - result.size());
      statistics.recordHits(result.size());
      return result;
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
          Map<K, V> loaded = cacheLoader.get().loadAll(keys);
          for (Map.Entry<? extends K, ? extends V> entry : loaded.entrySet()) {
            putNoCopyOrAwait(entry.getKey(), entry.getValue(), false);
          }
          completionListener.onCompletion();
          return;
        }

        List<K> keysToLoad = keys.stream()
            .filter(key -> !cache.asMap().containsKey(key))
            .collect(Collectors.<K>toList());
        Map<K, V> result = cacheLoader.get().loadAll(keysToLoad);
        for (Map.Entry<K, V> entry : result.entrySet()) {
          if ((entry.getKey() != null) && (entry.getValue() != null)) {
            putIfAbsentNoAwait(entry.getKey(), entry.getValue(), false);
          }
        }
        completionListener.onCompletion();
      } catch (CacheLoaderException e) {
        completionListener.onException(e);
      } catch (Exception e) {
        completionListener.onException(new CacheLoaderException(e));
      } finally {
        dispatcher.ignoreSynchronous();
      }
    });
  }

  @Override
  public void put(K key, V value) {
    requireNotClosed();
    putNoCopyOrAwait(key, value, true);
    dispatcher.awaitSynchronous();
    statistics.recordPuts(1);
  }

  @Override
  public V getAndPut(K key, V value) {
    requireNotClosed();
    V val = putNoCopyOrAwait(key, value, true);
    dispatcher.awaitSynchronous();
    statistics.recordPuts(1);

    if (val == null) {
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
    }
    return copyOf(val);
  }

  protected V putNoCopyOrAwait(K key, V value, boolean publishToWriter) {
    requireNonNull(key);
    requireNonNull(value);

    @SuppressWarnings("unchecked")
    V[] replaced = (V[]) new Object[1];
    cache.asMap().compute(copyOf(key), (k, oldValue) -> {
      V newValue = copyOf(value);
      if (publishToWriter) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      }
      if (oldValue == null) {
        dispatcher.publishCreated(this, key, newValue);
      } else {
        replaced[0] = oldValue;
        dispatcher.publishUpdated(this, key, oldValue, newValue);
      }
      return newValue;
    });
    return replaced[0];
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    requireNotClosed();
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      requireNonNull(entry.getKey());
      requireNonNull(entry.getValue());
    }
    CacheWriterException e = writeAllToCacheWriter(map);
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      putNoCopyOrAwait(entry.getKey(), entry.getValue(), false);
    }
    statistics.recordPuts(map.size());
    dispatcher.awaitSynchronous();
    if (e != null) {
      throw e;
    }
  }

  @Override
  public boolean putIfAbsent(K key, V value) {
    requireNotClosed();
    requireNonNull(value);

    boolean added = putIfAbsentNoAwait(key, value, true);
    dispatcher.awaitSynchronous();
    if (added) {
      statistics.recordPuts(1L);
    }
    return added;
  }

  private boolean putIfAbsentNoAwait(K key, V value, boolean publishToCacheWriter) {
    boolean[] absent = { false };
    cache.get(copyOf(key), k -> {
      absent[0] = true;
      V copy = copyOf(value);
      if (publishToCacheWriter) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      }
      dispatcher.publishCreated(this, key, copy);
      return copy;
    });
    return absent[0];
  }

  @Override
  public boolean remove(K key) {
    requireNotClosed();
    requireNonNull(key);

    publishToCacheWriter(writer::delete, () -> key);
    V value = removeNoCopyOrAwait(key);
    dispatcher.awaitSynchronous();
    if (value != null) {
      statistics.recordRemovals(1L);
      return true;
    }
    return false;
  }

  private V removeNoCopyOrAwait(K key) {
    @SuppressWarnings("unchecked")
    V[] removed = (V[]) new Object[1];
    cache.asMap().computeIfPresent(key, (k, value) -> {
      dispatcher.publishRemoved(this, key, value);
      removed[0] = value;
      return null;
    });
    return removed[0];
  }

  @Override
  public boolean remove(K key, V oldValue) {
    requireNotClosed();
    requireNonNull(key);
    requireNonNull(oldValue);

    boolean[] removed = { false };
    cache.asMap().computeIfPresent(key, (k, value) -> {
      if (oldValue.equals(value)) {
        publishToCacheWriter(writer::delete, () -> key);
        dispatcher.publishRemoved(this, key, value);
        removed[0] = true;
        return null;
      }
      return value;
    });
    dispatcher.awaitSynchronous();
    if (removed[0]) {
      statistics.recordRemovals(1L);
      statistics.recordHits(1L);
    } else {
      statistics.recordMisses(1L);
    }
    return removed[0];
  }

  @Override
  public V getAndRemove(K key) {
    requireNotClosed();
    requireNonNull(key);

    publishToCacheWriter(writer::delete, () -> key);
    V value = removeNoCopyOrAwait(key);
    dispatcher.awaitSynchronous();
    if (value != null) {
      statistics.recordHits(1L);
      statistics.recordRemovals(1L);
    } else {
      statistics.recordMisses(1L);
    }
    return copyOf(value);
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNotClosed();
    requireNonNull(oldValue);
    requireNonNull(newValue);
    boolean[] replaced = { false };
    boolean[] found = { false };
    cache.asMap().computeIfPresent(key, (k, value) -> {
      found[0] = true;
      if (value.equals(oldValue)) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
        dispatcher.publishUpdated(this, key, value, copyOf(newValue));
        replaced[0] = true;
        return newValue;
      }
      return value;
    });
    dispatcher.awaitSynchronous();
    if (replaced[0]) {
      statistics.recordPuts(1L);
    }
    if (found[0]) {
      statistics.recordHits(1L);
    } else {
      statistics.recordMisses(1L);
    }
    return replaced[0];
  }

  @Override
  public boolean replace(K key, V value) {
    requireNotClosed();
    V oldValue = replaceNoCopyOrAwait(key, value);
    dispatcher.awaitSynchronous();
    if (oldValue == null) {
      statistics.recordMisses(1L);
      return false;
    }
    statistics.recordHits(1L);
    statistics.recordPuts(1L);
    return true;
  }

  @Override
  public V getAndReplace(K key, V value) {
    requireNotClosed();
    V oldValue = replaceNoCopyOrAwait(key, value);
    dispatcher.awaitSynchronous();
    if (oldValue == null) {
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
      statistics.recordPuts(1L);
    }
    return copyOf(oldValue);
  }

  private V replaceNoCopyOrAwait(K key, V value) {
    requireNonNull(value);
    V copy = copyOf(value);
    @SuppressWarnings("unchecked")
    V[] replaced = (V[]) new Object[1];
    cache.asMap().computeIfPresent(key, (k, v) -> {
      publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      dispatcher.publishUpdated(this, key, value, copy);
      replaced[0] = v;
      return copy;
    });
    return replaced[0];
  }

  @Override
  public void removeAll(Set<? extends K> keys) {
    requireNotClosed();
    CacheWriterException e = deleteAllToCacheWriter(keys);
    int removed = 0;
    for (K key : keys) {
      V value = removeNoCopyOrAwait(key);
      if (value != null) {
        removed++;
      }
    }
    dispatcher.awaitSynchronous();
    statistics.recordRemovals(removed);
    if (e != null) {
      throw e;
    }
  }

  @Override
  public void removeAll() {
    Set<K> keys = configuration.isWriteThrough()
        ? new HashSet<>(cache.asMap().keySet())
        : cache.asMap().keySet();
    removeAll(keys);
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
        if (value == null) {
          if (entry.exists()) {
            statistics.recordPuts(1L);
            publishToCacheWriter(writer::write, () -> entry);
            dispatcher.publishCreated(this, key, entry.getValue());
          } else if (!entry.wasCreated()) {
            publishToCacheWriter(writer::delete, () -> key);
            dispatcher.publishRemoved(this, key, value);
          }
          statistics.recordMisses(1L);
        } else {
          if (!entry.exists()) {
            publishToCacheWriter(writer::delete, () -> key);
            dispatcher.publishRemoved(this, key, value);
            statistics.recordRemovals(1L);
          } else if (!entry.getValue().equals(value)) {
            statistics.recordPuts(1L);
            publishToCacheWriter(writer::write, () -> entry);
            dispatcher.publishUpdated(this, key, value, entry.getValue());
          }
          statistics.recordHits(1L);
        }
        return entry.getValue();
      } catch (EntryProcessorException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EntryProcessorException(e);
      }
    });
    dispatcher.awaitSynchronous();

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
    synchronized (configuration) {
      if (!isClosed()) {
        enableManagement(false);
        enableStatistics(false);
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
    requireNotClosed();
    configuration.addCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
    dispatcher.register(cacheEntryListenerConfiguration);
  }

  @Override
  public void deregisterCacheEntryListener(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    requireNotClosed();
    configuration.removeCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
    dispatcher.deregister(cacheEntryListenerConfiguration);
  }

  @Override
  public Iterator<Cache.Entry<K, V>> iterator() {
    requireNotClosed();

    return new Iterator<Cache.Entry<K, V>>() {
      final Iterator<Map.Entry<K, V>> delegate = cache.asMap().entrySet().iterator();
      Map.Entry<K, V> entry = null;

      @Override
      public boolean hasNext() {
        return delegate.hasNext();
      }

      @Override
      public Cache.Entry<K, V> next() {
        entry = delegate.next();
        return new EntryProxy<K, V>(copyOf(entry.getKey()), copyOf(entry.getValue()));
      }

      @Override
      public void remove() {
        if (entry == null) {
          throw new IllegalStateException();
        }
        CacheProxy.this.remove(entry.getKey(), entry.getValue());
        entry = null;
      }
    };
  }

  void enableManagement(boolean enabled) {
    requireNotClosed();

    synchronized (configuration) {
      if (enabled) {
        JmxRegistration.registerMXBean(this, cacheMXBean, MBeanType.Configuration);
      } else {
        JmxRegistration.unregisterMXBean(this, MBeanType.Configuration);
      }
      configuration.setManagementEnabled(enabled);
    }
  }

  void enableStatistics(boolean enabled) {
    requireNotClosed();

    synchronized (configuration) {
      if (enabled) {
        JmxRegistration.registerMXBean(this, statistics, MBeanType.Statistics);
      } else {
        JmxRegistration.unregisterMXBean(this, MBeanType.Statistics);
      }
      statistics.enable(enabled);
      configuration.setStatisticsEnabled(enabled);
    }
  }


  /** Checks that the cache is not closed. */
  protected final void requireNotClosed() {
    if (isClosed()) {
      throw new IllegalStateException();
    }
  }

  protected <T> void publishToCacheWriter(Consumer<T> action, Supplier<T> data) {
    if (!configuration.isWriteThrough()) {
      return;
    }
    try {
      action.accept(data.get());
    } catch (CacheWriterException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheWriterException("Exception in CacheWriter", e);
    }
  }

  protected <T> CacheWriterException writeAllToCacheWriter(Map<? extends K, ? extends V> map) {
    if (!configuration.isWriteThrough()  || map.isEmpty()) {
      return null;
    }
    List<Cache.Entry<? extends K, ? extends V>> entries = map.entrySet().stream()
        .map(entry -> new EntryProxy<>(entry.getKey(), entry.getValue()))
        .collect(Collectors.<Cache.Entry<? extends K, ? extends V>>toList());
    try {
      writer.writeAll(entries);
      return null;
    } catch (RuntimeException e) {
      for (Cache.Entry<? extends K, ? extends V> entry : entries) {
        map.remove(entry.getKey());
      }
      if (e instanceof CacheWriterException) {
        return (CacheWriterException) e;
      }
      return new CacheWriterException("Exception in CacheWriter", e);
    }
  }

  protected <T> CacheWriterException deleteAllToCacheWriter(Set<? extends K> keys) {
    if (!configuration.isWriteThrough() || keys.isEmpty()) {
      return null;
    }
    List<K> keysToDelete = new ArrayList<>(keys);
    try {
      writer.deleteAll(keysToDelete);
      return null;
    } catch (RuntimeException e) {
      keys.removeAll(keysToDelete);
      if (e instanceof CacheWriterException) {
        return (CacheWriterException) e;
      }
      return new CacheWriterException("Exception in CacheWriter", e);
    }
  }
}
