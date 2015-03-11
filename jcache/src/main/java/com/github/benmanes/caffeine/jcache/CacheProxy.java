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
import java.util.NoSuchElementException;
import java.util.Objects;
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
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;
import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.copy.CopyStrategy;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.integration.DisabledCacheWriter;
import com.github.benmanes.caffeine.jcache.management.JCacheMXBean;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.github.benmanes.caffeine.jcache.management.JmxRegistration;
import com.github.benmanes.caffeine.jcache.management.JmxRegistration.MBeanType;
import com.github.benmanes.caffeine.jcache.processor.EntryProcessorEntry;
import com.github.benmanes.caffeine.jcache.processor.EntryProcessorEntry.Action;

/**
 * An implementation of JSR-107 {@link Cache} backed by a Caffeine cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class CacheProxy<K, V> implements Cache<K, V> {
  private final com.github.benmanes.caffeine.cache.Cache<K, Expirable<V>> cache;
  private final CaffeineConfiguration<K, V> configuration;
  private final CopyStrategy copyStrategy;
  private final CacheManager cacheManager;
  private final CacheWriter<K, V> writer;
  private final JCacheMXBean cacheMXBean;
  private final Ticker ticker;
  private final String name;

  protected final Optional<CacheLoader<K, V>> cacheLoader;
  protected final JCacheStatisticsMXBean statistics;
  protected final EventDispatcher<K, V> dispatcher;
  protected final ExpiryPolicy expiry;

  private volatile boolean closed;

  public CacheProxy(String name, CacheManager cacheManager,
      CaffeineConfiguration<K, V> configuration,
      com.github.benmanes.caffeine.cache.Cache<K, Expirable<V>> cache,
      EventDispatcher<K, V> dispatcher, Optional<CacheLoader<K, V>> cacheLoader,
      ExpiryPolicy expiry, Ticker ticker) {
    this.configuration = requireNonNull(configuration);
    this.cacheManager = requireNonNull(cacheManager);
    this.cacheLoader = requireNonNull(cacheLoader);
    this.dispatcher = requireNonNull(dispatcher);
    this.expiry = requireNonNull(expiry);
    this.ticker = requireNonNull(ticker);
    this.cache = requireNonNull(cache);
    this.name = requireNonNull(name);

    copyStrategy = configuration.isStoreByValue()
        ? configuration.getCopyStrategyFactory().create()
        : CopyStrategy.identity();
    writer = configuration.hasCacheWriter()
        ? configuration.getCacheWriter()
        : DisabledCacheWriter.get();
    cacheMXBean = new JCacheMXBean(this);
    statistics = new JCacheStatisticsMXBean();
  }

  @Override
  public boolean containsKey(K key) {
    requireNotClosed();
    Expirable<V> expirable = cache.asMap().get(key);
    if (expirable == null) {
      return false;
    }
    if (expirable.hasExpired(currentTimeMillis())) {
      if (cache.asMap().remove(key, expirable)) {
        dispatcher.publishExpired(this, key, expirable.get());
        dispatcher.awaitSynchronous();
        statistics.recordEvictions(1);
      }
      return false;
    }
    return true;
  }

  @Override
  public V get(K key) {
    Expirable<V> expirable = doSafely(() -> cache.getIfPresent(key));

    long now = currentTimeMillis();
    if (expirable == null) {
      statistics.recordMisses(1L);
      return null;
    } else if (expirable.hasExpired(now)) {
      if (cache.asMap().remove(key, expirable)) {
        dispatcher.publishExpired(this, key, expirable.get());
        dispatcher.awaitSynchronous();
        statistics.recordEvictions(1);
      }
      statistics.recordMisses(1L);
      return null;
    }
    long expireTimeMS = expireTimeMS(expiry::getExpiryForAccess);
    expirable.setExpireTimeMS(expireTimeMS);
    V value = copyValue(expirable);
    if (value == null) {
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
    }
    return value;
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    Map<K, V> result = doSafely(() -> copyMap(cache.getAllPresent(keys)));
    statistics.recordMisses(keys.size() - result.size());
    statistics.recordHits(result.size());
    return result;
  }

  @Override
  public void loadAll(Set<? extends K> keys, boolean replaceExistingValues,
      CompletionListener completionListener) {
    requireNotClosed();
    keys.stream().forEach(Objects::requireNonNull);

    if (!cacheLoader.isPresent()) {
      return;
    }
    ForkJoinPool.commonPool().execute(() -> {
      try {
        if (replaceExistingValues) {
          int[] ignored = { 0 };
          Map<K, V> loaded = cacheLoader.get().loadAll(keys);
          for (Map.Entry<? extends K, ? extends V> entry : loaded.entrySet()) {
            putNoCopyOrAwait(entry.getKey(), entry.getValue(), false, ignored);
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
    int[] puts = { 0 };
    putNoCopyOrAwait(key, value, true, puts);
    dispatcher.awaitSynchronous();
    statistics.recordPuts(puts[0]);
  }

  @Override
  public V getAndPut(K key, V value) {
    requireNotClosed();
    int[] puts = { 0 };
    V val = putNoCopyOrAwait(key, value, true, puts);
    dispatcher.awaitSynchronous();
    statistics.recordPuts(puts[0]);

    if (val == null) {
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
    }
    return copyOf(val);
  }

  protected V putNoCopyOrAwait(K key, V value, boolean publishToWriter, int[] puts) {
    requireNonNull(key);
    requireNonNull(value);

    @SuppressWarnings("unchecked")
    V[] replaced = (V[]) new Object[1];
    cache.asMap().compute(copyOf(key), (k, expirable) -> {
      V newValue = copyOf(value);
      if (publishToWriter) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      }
      if ((expirable != null) && expirable.hasExpired(ticker.read())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        expirable = null;
      }
      boolean created = (expirable == null);
      long expireTimeMS = expireTimeMS(created
          ? expiry::getExpiryForCreation
          : expiry::getExpiryForUpdate);
      if (expireTimeMS == 0) {
        replaced[0] = created ? null : expirable.get();
        return null;
      } else if (created) {
        dispatcher.publishCreated(this, key, newValue);
      } else {
        replaced[0] = expirable.get();
        dispatcher.publishUpdated(this, key, expirable.get(), newValue);
      }
      puts[0]++;
      return new Expirable<>(newValue, expireTimeMS);
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
    int[] puts = { 0 };
    CacheWriterException e = writeAllToCacheWriter(map);
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      putNoCopyOrAwait(entry.getKey(), entry.getValue(), false, puts);
    }
    statistics.recordPuts(puts[0]);
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
    cache.asMap().compute(copyOf(key), (k, expirable) -> {
      if ((expirable != null) && expirable.hasExpired(ticker.read())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        expirable = null;
      }
      if (expirable != null) {
        return expirable;
      }

      absent[0] = true;
      long expireTimeMS = expireTimeMS(expiry::getExpiryForCreation);
      if (expireTimeMS == 0) {
        return null;
      }
      if (publishToCacheWriter) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      }
      V copy = copyOf(value);
      dispatcher.publishCreated(this, key, copy);
      return new Expirable<>(copy, expireTimeMS);
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
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (expirable.hasExpired(ticker.read())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }

      dispatcher.publishRemoved(this, key, expirable.get());
      removed[0] = expirable.get();
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
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (expirable.hasExpired(ticker.read())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }
      if (oldValue.equals(expirable.get())) {
        publishToCacheWriter(writer::delete, () -> key);
        dispatcher.publishRemoved(this, key, expirable.get());
        removed[0] = true;
        return null;
      }
      setExpirationTime(expirable, currentTimeMillis(), expiry::getExpiryForAccess);
      return expirable;
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
    boolean[] found = { false };
    boolean[] replaced = { false };
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (expirable.hasExpired(ticker.read())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }

      found[0] = true;
      Expirable<V> result;
      if (oldValue.equals(expirable.get())) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, expirable.get()));
        dispatcher.publishUpdated(this, key, expirable.get(), copyOf(newValue));
        long expireTimeMS = expireTimeMS(expiry::getExpiryForUpdate);
        result = new Expirable<>(newValue, expireTimeMS);
        replaced[0] = true;
      } else {
        result = expirable;
        setExpirationTime(expirable, currentTimeMillis(), expiry::getExpiryForAccess);
      }
      return result;
    });
    statistics.recordPuts(replaced[0] ? 1L : 0L);
    statistics.recordMisses(found[0] ? 0L : 1L);
    statistics.recordHits(found[0] ? 1L : 0L);
    dispatcher.awaitSynchronous();
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
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (expirable.hasExpired(ticker.read())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }

      publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      long expireTimeMS = expireTimeMS(expiry::getExpiryForUpdate);
      dispatcher.publishUpdated(this, key, expirable.get(), copy);
      replaced[0] = expirable.get();
      return new Expirable<>(copy, expireTimeMS);
    });
    return replaced[0];
  }

  @Override
  public void removeAll(Set<? extends K> keys) {
    requireNotClosed();
    CacheWriterException e = deleteAllToCacheWriter(keys);
    long removed = keys.stream()
        .map(this::removeNoCopyOrAwait)
        .filter(Objects::nonNull)
        .count();
    statistics.recordRemovals(removed);
    dispatcher.awaitSynchronous();
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

  /** @return the cache's configuration */
  public CaffeineConfiguration<K, V> getConfiguration() {
    return configuration;
  }

  @Override
  public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... arguments)
      throws EntryProcessorException {
    requireNonNull(entryProcessor);
    requireNonNull(arguments);
    requireNotClosed();

    Object[] result = new Object[1];

    cache.asMap().compute(copyOf(key), (k, expirable) -> {
      V value;
      long now = currentTimeMillis();
      if ((expirable == null) || expirable.hasExpired(now)) {
        value = null;
      } else {
        value = expirable.get();
      }
      boolean loaded = false;
      if ((value == null) && cacheLoader.isPresent() && configuration.isReadThrough()) {
        try {
          value = cacheLoader.get().load(key);
          loaded = true;
        } catch (RuntimeException ignored) {}
      }
      EntryProcessorEntry<K, V> entry = new EntryProcessorEntry<>(
          key, value, (expirable != null), loaded);
      try {
        result[0] = entryProcessor.process(entry, arguments);
        long expireTimeMS = postProcess(key, value, entry, loaded);
        return entry.exists() ? new Expirable<>(entry.getValue(), expireTimeMS) : null;
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

  /** Returns the expiration time after post-processing the entry. */
  private long postProcess(K key, V value, EntryProcessorEntry<K, V> entry, boolean loaded) {
    long expireTimeMS = loaded ? expireTimeMS(expiry::getExpiryForCreation) : Long.MAX_VALUE;

    if (value == null) {
      if (entry.exists()) {
        statistics.recordPuts(1L);
        publishToCacheWriter(writer::write, () -> entry);
        dispatcher.publishCreated(this, key, entry.getValue());
        expireTimeMS = expireTimeMS(expiry::getExpiryForCreation);
      } else if (!entry.wasMutated()) {
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
    if (entry.getAction() == Action.UPDATED) {
      expireTimeMS = expireTimeMS(expiry::getExpiryForUpdate);
    } else if (entry.getAction() == Action.CREATED) {
      expireTimeMS = expireTimeMS(expiry::getExpiryForCreation);
    } else if (entry.getAction() == Action.READ) {
      expireTimeMS = expireTimeMS(expiry::getExpiryForAccess);
    }
    return expireTimeMS;
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
    return new EntryIterator();
  }

  /** Enables or disables the configuration management JMX bean. */
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

  /** Enables or disables the statistics JMX bean. */
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

  /** Performs the action with the cache writer if write-through is enabled. */
  private <T> void publishToCacheWriter(Consumer<T> action, Supplier<T> data) {
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

  /** Writes all of the entries to the cache writer if write-through is enabled. */
  private <T> CacheWriterException writeAllToCacheWriter(Map<? extends K, ? extends V> map) {
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

  /** Deletes all of the entries using the cache writer if write-through is enabled. */
  private <T> CacheWriterException deleteAllToCacheWriter(Set<? extends K> keys) {
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

  /** Checks that the cache is not closed. */
  protected final void requireNotClosed() {
    if (isClosed()) {
      throw new IllegalStateException();
    }
  }

  /**
   * Returns a copy of the value if value-based caching is enabled.
   *
   * @param object the object to be copied
   * @param <T> the type of object being copied
   * @return a copy of the object if storing by value or the same instance if by reference
   */
  protected @Nullable <T> T copyOf(@Nullable T object) {
    return (object == null) ? null : copyStrategy.copy(object, cacheManager.getClassLoader());
  }

  /**
   * Returns a copy of the value if value-based caching is enabled.
   *
   * @param expirable the expirable value to be copied
   * @return a copy of the value if storing by value or the same instance if by reference
   */
  protected @Nullable V copyValue(@Nullable Expirable<V> expirable) {
    if (expirable == null) {
      return null;
    }
    return copyStrategy.copy(expirable.get(), cacheManager.getClassLoader());
  }

  /**
   * Returns a deep copy of the map if value-based caching is enabled.
   *
   * @param map the mapping of keys to expirable values
   * @return a copy of the mappings if storing by value or the same instance if by reference
   */
  protected Map<K, V> copyMap(Map<K, Expirable<V>> map) {
    final ClassLoader classLoader = cacheManager.getClassLoader();
    return map.entrySet().stream().collect(Collectors.toMap(
        entry -> copyStrategy.copy(entry.getKey(), classLoader),
        entry -> copyStrategy.copy(entry.getValue().get(), classLoader)));
  }

  /** @return an approximate of the current time in milliseconds */
  protected long currentTimeMillis() {
    return ticker.read() >> 10;
  }

  /**
   * Sets the expiration time based on the supplied expiration function.
   *
   * @param expirable the entry that was operated on
   * @param currentTimeMS the current time
   * @param expires the expiration function
   */
  protected static void setExpirationTime(Expirable<?> expirable,
      long currentTimeMS, Supplier<Duration> expires) {
    try {
      Duration duration = expires.get();
      long expireTimeMS = duration.getAdjustedTime(currentTimeMS);
      expirable.setExpireTimeMS(expireTimeMS);
    } catch (Exception e) {}
  }

  /**
   * Returns the time when the entry will expire based on the supplied expiration function.
   *
   * @param expires the expiration function
   * @return the time when the entry will expire
   */
  protected long expireTimeMS(Supplier<Duration> expires) {
    try {
      Duration duration = expires.get();
      return duration.isZero() ? 0 : duration.getAdjustedTime(currentTimeMillis());
    } catch (Exception e) {
      return Long.MAX_VALUE;
    }
  }

  /**
   * Performs the cache function, propagating the exception properly.
   *
   * @param task the operation to perform
   * @return the result if successful
   */
  protected <T> T doSafely(Supplier<T> task) {
    requireNotClosed();
    try {
      return task.get();
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    } finally {
      dispatcher.awaitSynchronous();
    }
  }

  /** An iterator to safely expose the cache entries. */
  private final class EntryIterator implements Iterator<Cache.Entry<K, V>> {
    final Iterator<Map.Entry<K, Expirable<V>>> delegate = cache.asMap().entrySet().iterator();
    Map.Entry<K, Expirable<V>> current;
    Map.Entry<K, Expirable<V>> cursor;

    @Override
    public boolean hasNext() {
      while ((cursor == null) && delegate.hasNext()) {
        Map.Entry<K, Expirable<V>> entry = delegate.next();
        long now = currentTimeMillis();
        if (!entry.getValue().hasExpired(now)) {
          setExpirationTime(entry.getValue(), now, expiry::getExpiryForAccess);
          cursor = entry;
        }
      }
      return (cursor != null);
    }

    @Override
    public Cache.Entry<K, V> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      current = cursor;
      cursor = null;
      return new EntryProxy<K, V>(copyOf(current.getKey()), copyValue(current.getValue()));
    }

    @Override
    public void remove() {
      if (current == null) {
        throw new IllegalStateException();
      }
      CacheProxy.this.remove(current.getKey(), current.getValue().get());
      current = null;
    }
  }
}
