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
import static java.util.Objects.requireNonNullElse;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableList;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.cache.Cache;
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

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.copy.Copier;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.event.Registration;
import com.github.benmanes.caffeine.jcache.integration.DisabledCacheWriter;
import com.github.benmanes.caffeine.jcache.management.JCacheMXBean;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.github.benmanes.caffeine.jcache.management.JmxRegistration;
import com.github.benmanes.caffeine.jcache.management.JmxRegistration.MBeanType;
import com.github.benmanes.caffeine.jcache.processor.EntryProcessorEntry;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

/**
 * An implementation of JSR-107 {@link Cache} backed by a Caffeine cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class CacheProxy<K, V> implements Cache<K, V> {
  private static final Logger logger = System.getLogger(CacheProxy.class.getName());

  protected final com.github.benmanes.caffeine.cache.Cache<K, @Nullable Expirable<V>> cache;
  protected final Optional<CacheLoader<K, V>> cacheLoader;
  protected final Set<CompletableFuture<?>> inFlight;
  protected final JCacheStatisticsMXBean statistics;
  protected final EventDispatcher<K, V> dispatcher;
  protected final Executor executor;
  protected final Ticker ticker;

  private final CaffeineConfiguration<K, V> configuration;
  private final CacheManager cacheManager;
  private final CacheWriter<K, V> writer;
  private final JCacheMXBean cacheMxBean;
  private final ExpiryPolicy expiry;
  private final Copier copier;
  private final String name;

  private volatile boolean closed;

  @SuppressWarnings({"this-escape", "TooManyParameters"})
  public CacheProxy(String name, Executor executor, CacheManager cacheManager,
      CaffeineConfiguration<K, V> configuration,
      com.github.benmanes.caffeine.cache.Cache<K, @Nullable Expirable<V>> cache,
      EventDispatcher<K, V> dispatcher, Optional<CacheLoader<K, V>> cacheLoader,
      ExpiryPolicy expiry, Ticker ticker, JCacheStatisticsMXBean statistics) {
    this.writer = requireNonNullElse(configuration.getCacheWriter(), DisabledCacheWriter.get());
    this.configuration = requireNonNull(configuration);
    this.cacheManager = requireNonNull(cacheManager);
    this.cacheLoader = requireNonNull(cacheLoader);
    this.dispatcher = requireNonNull(dispatcher);
    this.statistics = requireNonNull(statistics);
    this.executor = requireNonNull(executor);
    this.expiry = requireNonNull(expiry);
    this.ticker = requireNonNull(ticker);
    this.cache = requireNonNull(cache);
    this.name = requireNonNull(name);

    copier = configuration.isStoreByValue()
        ? configuration.getCopierFactory().create()
        : Copier.identity();
    cacheMxBean = new JCacheMXBean(this);
    inFlight = ConcurrentHashMap.newKeySet();
  }

  @Override
  public boolean containsKey(K key) {
    requireNotClosed();
    Expirable<V> expirable = cache.getIfPresent(key);
    if (expirable == null) {
      return false;
    }
    if (!expirable.isEternal() && expirable.hasExpired(currentTimeMillis())) {
      cache.asMap().computeIfPresent(key, (k, e) -> {
        if (e == expirable) {
          dispatcher.publishExpired(this, key, expirable.get());
          statistics.recordEvictions(1);
          return null;
        }
        return e;
      });
      dispatcher.awaitSynchronous();
      return false;
    }
    return true;
  }

  @Override
  public @Nullable V get(K key) {
    requireNotClosed();
    Expirable<V> expirable = cache.getIfPresent(key);
    if (expirable == null) {
      statistics.recordMisses(1L);
      return null;
    }

    long start;
    long millis;
    boolean statsEnabled = statistics.isEnabled();
    if (!expirable.isEternal()) {
      start = ticker.read();
      millis = nanosToMillis(start);
      if (expirable.hasExpired(millis)) {
        cache.asMap().computeIfPresent(key, (k, e) -> {
          if (e == expirable) {
            dispatcher.publishExpired(this, key, expirable.get());
            statistics.recordEvictions(1);
            return null;
          }
          return e;
        });
        dispatcher.awaitSynchronous();
        statistics.recordMisses(1L);
        return null;
      }
    } else if (statsEnabled) {
      start = ticker.read();
      millis = nanosToMillis(start);
    } else {
      start = millis = 0L;
    }

    setAccessExpireTime(key, expirable, millis);
    V value = copyValue(expirable);
    if (statsEnabled) {
      statistics.recordHits(1L);
      statistics.recordGetTime(ticker.read() - start);
    }
    return value;
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    requireNotClosed();

    boolean statsEnabled = statistics.isEnabled();
    long now = statsEnabled ? ticker.read() : 0L;

    Map<K, Expirable<V>> result = getAndFilterExpiredEntries(keys, /* updateAccessTime= */ true);

    if (statsEnabled) {
      statistics.recordGetTime(ticker.read() - now);
    }
    return copyMap(result);
  }

  /**
   * Returns all of the mappings present, expiring as required, and optionally updates their access
   * expiry time.
   */
  protected Map<K, Expirable<V>> getAndFilterExpiredEntries(
      Set<? extends K> keys, boolean updateAccessTime) {
    int[] expired = { 0 };
    long[] millis = { 0L };
    var result = new HashMap<K, Expirable<V>>(cache.getAllPresent(keys));
    result.entrySet().removeIf(entry -> {
      if (!entry.getValue().isEternal() && (millis[0] == 0L)) {
        millis[0] = currentTimeMillis();
      }
      if (entry.getValue().hasExpired(millis[0])) {
        cache.asMap().computeIfPresent(entry.getKey(), (k, expirable) -> {
          if (expirable == entry.getValue()) {
            dispatcher.publishExpired(this, entry.getKey(), entry.getValue().get());
            expired[0]++;
            return null;
          }
          return expirable;
        });
        return true;
      }
      if (updateAccessTime) {
        setAccessExpireTime(entry.getKey(), entry.getValue(), millis[0]);
      }
      return false;
    });

    statistics.recordHits(result.size());
    statistics.recordMisses(keys.size() - result.size());
    statistics.recordEvictions(expired[0]);
    return result;
  }

  @Override
  @SuppressWarnings({"CollectionUndefinedEquality", "FutureReturnValueIgnored"})
  public void loadAll(Set<? extends K> keys, boolean replaceExistingValues,
      CompletionListener completionListener) {
    requireNotClosed();
    keys.forEach(Objects::requireNonNull);
    CompletionListener listener = (completionListener == null)
        ? NullCompletionListener.INSTANCE
        : completionListener;

    if (cacheLoader.isEmpty()) {
      listener.onCompletion();
      return;
    }

    var future = CompletableFuture.runAsync(() -> {
      try {
        if (replaceExistingValues) {
          loadAllAndReplaceExisting(keys);
        } else {
          loadAllAndKeepExisting(keys);
        }
        listener.onCompletion();
      } catch (CacheLoaderException e) {
        listener.onException(e);
      } catch (RuntimeException e) {
        listener.onException(new CacheLoaderException(e));
      } finally {
        dispatcher.ignoreSynchronous();
      }
    }, executor);

    inFlight.add(future);
    future.whenComplete((r, e) -> inFlight.remove(future));
  }

  /** Performs the bulk load where the existing entries are replaced. */
  private void loadAllAndReplaceExisting(Set<? extends K> keys) {
    Map<K, V> loaded = cacheLoader.orElseThrow().loadAll(keys);
    for (var entry : loaded.entrySet()) {
      putNoCopyOrAwait(entry.getKey(), entry.getValue(), /* publishToWriter= */ false);
    }
  }

  /** Performs the bulk load where the existing entries are retained. */
  private void loadAllAndKeepExisting(Set<? extends K> keys) {
    List<K> keysToLoad = keys.stream()
        .filter(key -> !cache.asMap().containsKey(key))
        .collect(toUnmodifiableList());
    Map<K, V> result = cacheLoader.orElseThrow().loadAll(keysToLoad);
    for (var entry : result.entrySet()) {
      if ((entry.getKey() != null) && (entry.getValue() != null)) {
        putIfAbsentNoAwait(entry.getKey(), entry.getValue(), /* publishToWriter= */ false);
      }
    }
  }

  @Override
  public void put(K key, V value) {
    requireNotClosed();
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    var result = putNoCopyOrAwait(key, value, /* publishToWriter= */ true);
    dispatcher.awaitSynchronous();

    if (statsEnabled) {
      if (result.written) {
        statistics.recordPuts(1);
      }
      statistics.recordPutTime(ticker.read() - start);
    }
  }

  @Override
  public @Nullable V getAndPut(K key, V value) {
    requireNotClosed();
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    var result = putNoCopyOrAwait(key, value, /* publishToWriter= */ true);
    dispatcher.awaitSynchronous();

    if (statsEnabled) {
      if (result.oldValue == null) {
        statistics.recordMisses(1L);
      } else {
        statistics.recordHits(1L);
      }
      if (result.written) {
        statistics.recordPuts(1);
      }
      long duration = ticker.read() - start;
      statistics.recordGetTime(duration);
      statistics.recordPutTime(duration);
    }
    return copyOf(result.oldValue);
  }

  /**
   * Associates the specified value with the specified key in the cache.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param publishToWriter if the writer should be notified
   * @return the old value
   */
  @CanIgnoreReturnValue
  protected PutResult<V> putNoCopyOrAwait(K key, V value, boolean publishToWriter) {
    requireNonNull(key);
    requireNonNull(value);

    var result = new PutResult<V>();
    cache.asMap().compute(copyOf(key), (K k, @Var Expirable<V> expirable) -> {
      V newValue = copyOf(value);
      if (publishToWriter) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<>(key, value));
      }
      if ((expirable != null) && !expirable.isEternal()
          && expirable.hasExpired(currentTimeMillis())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        expirable = null;
      }
      @Var long expireTimeMillis = getWriteExpireTimeMillis((expirable == null));
      if ((expirable != null) && (expireTimeMillis == Long.MIN_VALUE)) {
        expireTimeMillis = expirable.getExpireTimeMillis();
      }
      if (expireTimeMillis == 0) {
        // The TCK asserts that expired entry is not counted in the puts stats, despite the javadoc
        // saying otherwise. See CacheMBStatisticsBeanTest.testExpiryOnCreation()
        result.written = false;

        // The TCK asserts that a create is not published, so skipping on update for consistency.
        // See CacheExpiryTest.expire_whenCreated_CreatedExpiryPolicy()
        result.oldValue = (expirable == null) ? null : expirable.get();

        dispatcher.publishExpired(this, key, value);
        return null;
      } else if (expirable == null) {
        dispatcher.publishCreated(this, key, newValue);
      } else {
        result.oldValue = expirable.get();
        dispatcher.publishUpdated(this, key, expirable.get(), newValue);
      }
      result.written = true;
      return new Expirable<>(newValue, expireTimeMillis);
    });
    return result;
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    requireNotClosed();
    for (var entry : map.entrySet()) {
      requireNonNull(entry.getKey());
      requireNonNull(entry.getValue());
    }

    @Var CacheWriterException error = null;
    @Var Set<? extends K> failedKeys = Set.of();
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;
    if (configuration.isWriteThrough() && !map.isEmpty()) {
      var entries = new ArrayList<Cache.Entry<? extends K, ? extends V>>(map.size());
      for (var entry : map.entrySet()) {
        entries.add(new EntryProxy<>(entry.getKey(), entry.getValue()));
      }
      try {
        writer.writeAll(entries);
      } catch (CacheWriterException e) {
        failedKeys = entries.stream().map(Cache.Entry::getKey).collect(toSet());
        error = e;
      } catch (RuntimeException e) {
        failedKeys = entries.stream().map(Cache.Entry::getKey).collect(toSet());
        error = new CacheWriterException("Exception in CacheWriter", e);
      }
    }

    @Var int puts = 0;
    for (var entry : map.entrySet()) {
      if (!failedKeys.contains(entry.getKey())) {
        var result = putNoCopyOrAwait(entry.getKey(),
            entry.getValue(), /* publishToWriter= */ false);
        if (result.written) {
          puts++;
        }
      }
    }
    dispatcher.awaitSynchronous();

    if (statsEnabled) {
      statistics.recordPuts(puts);
      statistics.recordPutTime(ticker.read() - start);
    }
    if (error != null) {
      throw error;
    }
  }

  @Override
  public boolean putIfAbsent(K key, V value) {
    requireNotClosed();
    requireNonNull(value);
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    boolean added = putIfAbsentNoAwait(key, value, /* publishToWriter= */ true);
    dispatcher.awaitSynchronous();

    if (statsEnabled) {
      if (added) {
        statistics.recordPuts(1L);
        statistics.recordMisses(1L);
      } else {
        statistics.recordHits(1L);
      }
      statistics.recordPutTime(ticker.read() - start);
    }
    return added;
  }

  /**
   * Associates the specified value with the specified key in the cache if there is no existing
   * mapping.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param publishToWriter if the writer should be notified
   * @return if the mapping was successful
   */
  @CanIgnoreReturnValue
  private boolean putIfAbsentNoAwait(K key, V value, boolean publishToWriter) {
    boolean[] absent = { false };
    cache.asMap().compute(copyOf(key), (K k, @Var Expirable<V> expirable) -> {
      if ((expirable != null) && !expirable.isEternal()
          && expirable.hasExpired(currentTimeMillis())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        expirable = null;
      }
      if (expirable != null) {
        return expirable;
      }
      if (publishToWriter) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<>(key, value));
      }

      absent[0] = true;
      V copy = copyOf(value);
      long expireTimeMillis = getWriteExpireTimeMillis(/* created= */ true);
      if (expireTimeMillis == 0) {
        // The TCK asserts that a create is not published in
        // CacheExpiryTest.expire_whenCreated_CreatedExpiryPolicy()
        dispatcher.publishExpired(this, key, copy);
        return null;
      } else {
        dispatcher.publishCreated(this, key, copy);
        return new Expirable<>(copy, expireTimeMillis);
      }
    });
    return absent[0];
  }

  @Override
  public boolean remove(K key) {
    requireNotClosed();
    requireNonNull(key);
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    publishToCacheWriter(writer::delete, () -> key);
    V value = removeNoCopyOrAwait(key);
    dispatcher.awaitSynchronous();

    if (statsEnabled) {
      statistics.recordRemoveTime(ticker.read() - start);
    }
    if (value != null) {
      statistics.recordRemovals(1L);
      return true;
    }
    return false;
  }

  /**
   * Removes the mapping from the cache without store-by-value copying nor waiting for synchronous
   * listeners to complete.
   *
   * @param key key whose mapping is to be removed from the cache
   * @return the old value
   */
  private V removeNoCopyOrAwait(K key) {
    @SuppressWarnings("unchecked")
    var removed = (V[]) new Object[1];
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (!expirable.isEternal() && expirable.hasExpired(currentTimeMillis())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
      } else {
        dispatcher.publishRemoved(this, key, expirable.get());
        removed[0] = expirable.get();
      }
      return null;
    });
    return removed[0];
  }

  @Override
  @CanIgnoreReturnValue
  public boolean remove(K key, V oldValue) {
    requireNotClosed();
    requireNonNull(key);
    requireNonNull(oldValue);

    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    boolean[] removed = { false };
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      long millis = expirable.isEternal()
          ? 0L
          : nanosToMillis((start == 0L) ? ticker.read() : start);
      if (expirable.hasExpired(millis)) {
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
      setAccessExpireTime(key, expirable, millis);
      return expirable;
    });
    dispatcher.awaitSynchronous();
    if (statsEnabled) {
      if (removed[0]) {
        statistics.recordRemovals(1L);
        statistics.recordHits(1L);
      } else {
        statistics.recordMisses(1L);
      }
      statistics.recordRemoveTime(ticker.read() - start);
    }
    return removed[0];
  }

  @Override
  public V getAndRemove(K key) {
    requireNotClosed();
    requireNonNull(key);
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    publishToCacheWriter(writer::delete, () -> key);
    V value = removeNoCopyOrAwait(key);
    dispatcher.awaitSynchronous();
    V copy = copyOf(value);

    if (statsEnabled) {
      if (value == null) {
        statistics.recordMisses(1L);
      } else {
        statistics.recordHits(1L);
        statistics.recordRemovals(1L);
      }
      long duration = ticker.read() - start;
      statistics.recordRemoveTime(duration);
      statistics.recordGetTime(duration);
    }
    return copy;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNotClosed();
    requireNonNull(oldValue);
    requireNonNull(newValue);

    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    boolean[] found = { false };
    boolean[] replaced = { false };
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      long millis = expirable.isEternal()
          ? 0L
          : nanosToMillis((start == 0L) ? ticker.read() : start);
      if (expirable.hasExpired(millis)) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }

      found[0] = true;
      Expirable<V> result;
      if (oldValue.equals(expirable.get())) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<>(key, expirable.get()));
        dispatcher.publishUpdated(this, key, expirable.get(), copyOf(newValue));
        @Var long expireTimeMillis = getWriteExpireTimeMillis(/* created= */ false);
        if (expireTimeMillis == Long.MIN_VALUE) {
          expireTimeMillis = expirable.getExpireTimeMillis();
        }
        result = new Expirable<>(newValue, expireTimeMillis);
        replaced[0] = true;
      } else {
        result = expirable;
        setAccessExpireTime(key, expirable, millis);
      }
      return result;
    });
    dispatcher.awaitSynchronous();

    if (statsEnabled) {
      statistics.recordPuts(replaced[0] ? 1L : 0L);
      statistics.recordMisses(found[0] ? 0L : 1L);
      statistics.recordHits(found[0] ? 1L : 0L);
      long duration = ticker.read() - start;
      statistics.recordGetTime(duration);
      statistics.recordPutTime(duration);
    }

    return replaced[0];
  }

  @Override
  public boolean replace(K key, V value) {
    requireNotClosed();
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    V oldValue = replaceNoCopyOrAwait(key, value);
    dispatcher.awaitSynchronous();
    if (oldValue == null) {
      statistics.recordMisses(1L);
      return false;
    }

    if (statsEnabled) {
      statistics.recordHits(1L);
      statistics.recordPuts(1L);
      statistics.recordPutTime(ticker.read() - start);
    }
    return true;
  }

  @Override
  public V getAndReplace(K key, V value) {
    requireNotClosed();
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;

    V oldValue = replaceNoCopyOrAwait(key, value);
    dispatcher.awaitSynchronous();
    V copy = copyOf(oldValue);

    if (statsEnabled) {
      if (oldValue == null) {
        statistics.recordMisses(1L);
      } else {
        statistics.recordHits(1L);
        statistics.recordPuts(1L);
      }
      long duration = ticker.read() - start;
      statistics.recordGetTime(duration);
      statistics.recordPutTime(duration);
    }
    return copy;
  }

  /**
   * Replaces the entry for the specified key only if it is currently mapped to some value. The
   * entry is not store-by-value copied nor does the method wait for synchronous listeners to
   * complete.
   *
   * @param key key with which the specified value is associated
   * @param value value to be associated with the specified key
   * @return the old value
   */
  private V replaceNoCopyOrAwait(K key, V value) {
    requireNonNull(value);
    V copy = copyOf(value);
    @SuppressWarnings("unchecked")
    var replaced = (V[]) new Object[1];
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (!expirable.isEternal() && expirable.hasExpired(currentTimeMillis())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }

      publishToCacheWriter(writer::write, () -> new EntryProxy<>(key, value));
      @Var long expireTimeMillis = getWriteExpireTimeMillis(/* created= */ false);
      if (expireTimeMillis == Long.MIN_VALUE) {
        expireTimeMillis = expirable.getExpireTimeMillis();
      }
      dispatcher.publishUpdated(this, key, expirable.get(), copy);
      replaced[0] = expirable.get();
      return new Expirable<>(copy, expireTimeMillis);
    });
    return replaced[0];
  }

  @Override
  public void removeAll(Set<? extends K> keys) {
    requireNotClosed();
    keys.forEach(Objects::requireNonNull);

    @Var CacheWriterException error = null;
    @Var Set<? extends K> failedKeys = Set.of();
    boolean statsEnabled = statistics.isEnabled();
    long start = statsEnabled ? ticker.read() : 0L;
    if (configuration.isWriteThrough() && !keys.isEmpty()) {
      var keysToWrite = new LinkedHashSet<>(keys);
      try {
        writer.deleteAll(keysToWrite);
      } catch (CacheWriterException e) {
        error = e;
        failedKeys = keysToWrite;
      } catch (RuntimeException e) {
        error = new CacheWriterException("Exception in CacheWriter", e);
        failedKeys = keysToWrite;
      }
    }

    @Var int removed = 0;
    for (var key : keys) {
      if (!failedKeys.contains(key) && (removeNoCopyOrAwait(key) != null)) {
        removed++;
      }
    }
    dispatcher.awaitSynchronous();

    if (statsEnabled) {
      statistics.recordRemovals(removed);
      statistics.recordRemoveTime(ticker.read() - start);
    }
    if (error != null) {
      throw error;
    }
  }

  @Override
  public void removeAll() {
    removeAll(cache.asMap().keySet());
  }

  @Override
  public void clear() {
    requireNotClosed();
    cache.invalidateAll();
  }

  @Override
  public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
    if (clazz.isInstance(configuration)) {
      synchronized (configuration) {
        return clazz.cast(configuration.immutableCopy());
      }
    }
    throw new IllegalArgumentException("The configuration class " + clazz
        + " is not supported by this implementation");
  }

  @Override
  public <T> @Nullable T invoke(K key,
      EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
    requireNonNull(entryProcessor);
    requireNonNull(arguments);
    requireNotClosed();

    Object[] result = new Object[1];
    BiFunction<K, Expirable<V>, Expirable<V>> remappingFunction = (k, expirable) -> {
      V value;
      @Var long millis = 0L;
      if ((expirable == null)
          || (!expirable.isEternal() && expirable.hasExpired(millis = currentTimeMillis()))) {
        statistics.recordMisses(1L);
        value = null;
      } else {
        value = copyValue(expirable);
        statistics.recordHits(1L);
      }
      var entry = new EntryProcessorEntry<>(key, value,
          configuration.isReadThrough() ? cacheLoader : Optional.empty());
      try {
        result[0] = entryProcessor.process(entry, arguments);
        return postProcess(expirable, entry, millis);
      } catch (EntryProcessorException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EntryProcessorException(e);
      }
    };
    try {
      cache.asMap().compute(copyOf(key), remappingFunction);
      dispatcher.awaitSynchronous();
    } catch (Throwable t) {
      dispatcher.ignoreSynchronous();
      throw t;
    }

    @SuppressWarnings("unchecked")
    var castedResult = (T) result[0];
    return castedResult;
  }

  /** Returns the updated expirable value after performing the post-processing actions. */
  @SuppressWarnings("fallthrough")
  @Nullable Expirable<V> postProcess(@Nullable Expirable<V> expirable,
      EntryProcessorEntry<K, V> entry, @Var long currentTimeMillis) {
    switch (entry.getAction()) {
      case NONE:
        if (expirable == null) {
          return null;
        } else if (expirable.isEternal()) {
          return expirable;
        }
        if (currentTimeMillis == 0) {
          currentTimeMillis = currentTimeMillis();
        }
        if (expirable.hasExpired(currentTimeMillis)) {
          dispatcher.publishExpired(this, entry.getKey(), expirable.get());
          statistics.recordEvictions(1);
          return null;
        }
        return expirable;
      case READ: {
        setAccessExpireTime(entry.getKey(), requireNonNull(expirable), 0L);
        return expirable;
      }
      case CREATED:
        this.publishToCacheWriter(writer::write, () -> entry);
        // fallthrough
      case LOADED: {
        statistics.recordPuts(1L);
        var value = requireNonNull(entry.getValue());
        dispatcher.publishCreated(this, entry.getKey(), value);
        return new Expirable<>(value, getWriteExpireTimeMillis(/* created= */ true));
      }
      case UPDATED: {
        statistics.recordPuts(1L);
        publishToCacheWriter(writer::write, () -> entry);
        requireNonNull(expirable, "Expected a previous value but was null");
        var value = requireNonNull(entry.getValue(), "Expected a new value but was null");
        dispatcher.publishUpdated(this, entry.getKey(), expirable.get(), value);
        @Var long expireTimeMillis = getWriteExpireTimeMillis(/* created= */ false);
        if (expireTimeMillis == Long.MIN_VALUE) {
          expireTimeMillis = expirable.getExpireTimeMillis();
        }
        return new Expirable<>(value, expireTimeMillis);
      }
      case DELETED:
        statistics.recordRemovals(1L);
        publishToCacheWriter(writer::delete, entry::getKey);
        if (expirable != null) {
          dispatcher.publishRemoved(this, entry.getKey(), expirable.get());
        }
        return null;
    }
    throw new IllegalStateException("Unknown state: " + entry.getAction());
  }

  @Override
  public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys,
      EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
    var results = new HashMap<K, EntryProcessorResult<T>>(keys.size(), 1.0f);
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
  public boolean isClosed() {
    return closed;
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

        @Var var thrown = shutdownExecutor();
        thrown = tryClose(expiry, thrown);
        thrown = tryClose(writer, thrown);
        thrown = tryClose(executor, thrown);
        thrown = tryClose(cacheLoader.orElse(null), thrown);
        for (Registration<K, V> registration : dispatcher.registrations()) {
          thrown = tryClose(registration.getCacheEntryListener(), thrown);
        }
        if (thrown != null) {
          logger.log(Level.WARNING, "Failure when closing cache resources", thrown);
        }
      }
    }
    cache.invalidateAll();
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private @Nullable Throwable shutdownExecutor() {
    if (executor instanceof ExecutorService) {
      @SuppressWarnings("PMD.CloseResource")
      var es = (ExecutorService) executor;
      es.shutdown();
    }

    @Var Throwable thrown = null;
    try {
      CompletableFuture
          .allOf(inFlight.toArray(CompletableFuture[]::new))
          .get(10, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      thrown = e;
    }
    inFlight.clear();
    return thrown;
  }

  /**
   * Attempts to close the resource. If an error occurs and an outermost exception is set, then adds
   * the error to the suppression list.
   *
   * @param o the resource to close if Closeable
   * @param outer the outermost error, or null if unset
   * @return the outermost error, or null if unset and successful
   */
  private static @Nullable Throwable tryClose(@Nullable Object o, @Nullable Throwable outer) {
    if (o instanceof AutoCloseable) {
      try {
        ((AutoCloseable) o).close();
      } catch (Throwable t) {
        if (outer == null) {
          return t;
        }
        outer.addSuppressed(t);
        return outer;
      }
    }
    return null;
  }

  @Override
  public <T> T unwrap(Class<T> clazz) {
    if (clazz.isInstance(cache)) {
      return clazz.cast(cache);
    } else if (clazz.isInstance(this)) {
      return clazz.cast(this);
    }
    throw new IllegalArgumentException("Unwrapping to " + clazz
        + " is not supported by this implementation");
  }

  @Override
  public void registerCacheEntryListener(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    requireNotClosed();
    synchronized (configuration) {
      configuration.addCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
      dispatcher.register(cacheEntryListenerConfiguration);
    }
  }

  @Override
  public void deregisterCacheEntryListener(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    requireNotClosed();
    synchronized (configuration) {
      configuration.removeCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
      dispatcher.deregister(cacheEntryListenerConfiguration);
    }
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
        JmxRegistration.registerMxBean(this, cacheMxBean, MBeanType.CONFIGURATION);
      } else {
        JmxRegistration.unregisterMxBean(this, MBeanType.CONFIGURATION);
      }
      configuration.setManagementEnabled(enabled);
    }
  }

  /** Enables or disables the statistics JMX bean. */
  void enableStatistics(boolean enabled) {
    requireNotClosed();

    synchronized (configuration) {
      if (enabled) {
        JmxRegistration.registerMxBean(this, statistics, MBeanType.STATISTICS);
      } else {
        JmxRegistration.unregisterMxBean(this, MBeanType.STATISTICS);
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
  @SuppressWarnings("NullAway")
  protected final <T> T copyOf(@Nullable T object) {
    if (object == null) {
      return null;
    }
    T copy = copier.copy(object, cacheManager.getClassLoader());
    return requireNonNull(copy);
  }

  /**
   * Returns a copy of the value if value-based caching is enabled.
   *
   * @param expirable the expirable value to be copied
   * @return a copy of the value if storing by value or the same instance if by reference
   */
  @SuppressWarnings("NullAway")
  protected final V copyValue(@Nullable Expirable<V> expirable) {
    if (expirable == null) {
      return null;
    }
    V copy = copier.copy(expirable.get(), cacheManager.getClassLoader());
    return requireNonNull(copy);
  }

  /**
   * Returns a deep copy of the map if value-based caching is enabled.
   *
   * @param map the mapping of keys to expirable values
   * @return a deep or shallow copy of the mappings depending on the store by value setting
   */
  @SuppressWarnings("CollectorMutability")
  protected final Map<K, V> copyMap(Map<K, Expirable<V>> map) {
    ClassLoader classLoader = cacheManager.getClassLoader();
    return map.entrySet().stream().collect(toMap(
        entry -> copier.copy(entry.getKey(), classLoader),
        entry -> copier.copy(entry.getValue().get(), classLoader)));
  }

  /** Returns the current time in milliseconds. */
  protected final long currentTimeMillis() {
    return nanosToMillis(ticker.read());
  }

  /** Returns the nanosecond time in milliseconds. */
  protected static long nanosToMillis(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  /**
   * Sets the access expiration time.
   *
   * @param key the entry's key
   * @param expirable the entry that was operated on
   * @param currentTimeMillis the current time, or 0 if not read yet
   */
  protected final void setAccessExpireTime(K key,
      Expirable<?> expirable, @Var long currentTimeMillis) {
    try {
      Duration duration = expiry.getExpiryForAccess();
      if (duration == null) {
        return;
      } else if (duration.isZero()) {
        expirable.setExpireTimeMillis(0L);
      } else if (duration.isEternal()) {
        expirable.setExpireTimeMillis(Long.MAX_VALUE);
      } else {
        if (currentTimeMillis == 0L) {
          currentTimeMillis = currentTimeMillis();
        }
        long expireTimeMillis = duration.getAdjustedTime(currentTimeMillis);
        expirable.setExpireTimeMillis(expireTimeMillis);
      }
      cache.policy().expireVariably().ifPresent(policy -> {
        policy.setExpiresAfter(key, duration.getDurationAmount(), duration.getTimeUnit());
      });
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to set the entry's expiration time", e);
    }
  }

  /**
   * Returns the time when the entry will expire.
   *
   * @param created if the write operation is an insert or an update
   * @return the time when the entry will expire, zero if it should expire immediately,
   *         Long.MIN_VALUE if it should not be changed, or Long.MAX_VALUE if eternal
   */
  protected final long getWriteExpireTimeMillis(boolean created) {
    try {
      Duration duration = created ? expiry.getExpiryForCreation() : expiry.getExpiryForUpdate();
      if (duration == null) {
        return Long.MIN_VALUE;
      } else if (duration.isZero()) {
        return 0L;
      } else if (duration.isEternal()) {
        return Long.MAX_VALUE;
      }
      return duration.getAdjustedTime(currentTimeMillis());
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to get the policy's expiration time", e);
      return Long.MIN_VALUE;
    }
  }

  /** An iterator to safely expose the cache entries. */
  final class EntryIterator implements Iterator<Cache.Entry<K, V>> {
    // NullAway does not yet understand the @NonNull annotation in the return type of asMap.
    @SuppressWarnings("NullAway")
    Iterator<Map.Entry<K, Expirable<V>>> delegate = cache.asMap().entrySet().iterator();
    Map.@Nullable Entry<K, Expirable<V>> current;
    Map.@Nullable Entry<K, Expirable<V>> cursor;

    @Override
    public boolean hasNext() {
      while ((cursor == null) && delegate.hasNext()) {
        Map.Entry<K, Expirable<V>> entry = delegate.next();
        long millis = entry.getValue().isEternal() ? 0L : currentTimeMillis();
        if (!entry.getValue().hasExpired(millis)) {
          setAccessExpireTime(entry.getKey(), entry.getValue(), millis);
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
      current = requireNonNull(cursor);
      cursor = null;
      return new EntryProxy<>(copyOf(current.getKey()), copyValue(current.getValue()));
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

  protected static final class PutResult<V> {
    @Nullable V oldValue;
    boolean written;
  }

  protected enum NullCompletionListener implements CompletionListener {
    INSTANCE;

    @Override
    public void onCompletion() {}

    @Override
    public void onException(Exception e) {}
  }
}
