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
package com.github.benmanes.caffeine.jcache.configuration;

import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Spliterator;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheWriter;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.jcache.copy.Copier;
import com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopier;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * A JCache configuration with Caffeine specific settings.
 * <p>
 * The initial settings disable <tt>store by value</tt> so that entries are not copied when crossing
 * the {@link javax.cache.Cache} API boundary. If enabled and the {@link Copier} is not explicitly
 * set, then the {@link JavaSerializationCopier} will be used. This differs from
 * {@link MutableConfiguration} which enables <tt>store by value</tt> at construction.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineConfiguration<K, V> implements CompleteConfiguration<K, V> {
  private static final Factory<Scheduler> DISABLED_SCHEDULER = Scheduler::disabledScheduler;
  private static final Factory<Copier> JAVA_COPIER = JavaSerializationCopier::new;
  private static final Factory<Executor> COMMON_POOL = ForkJoinPool::commonPool;
  private static final Factory<Ticker> SYSTEM_TICKER = Ticker::systemTicker;
  private static final long serialVersionUID = 1L;

  private final MutableConfiguration<K, V> delegate;
  private final boolean readOnly;

  private @Nullable Factory<Weigher<K, V>> weigherFactory;
  private @Nullable Factory<Expiry<K, V>> expiryFactory;

  private Factory<Scheduler> schedulerFactory;
  private Factory<Executor> executorFactory;
  private Factory<Copier> copierFactory;
  private Factory<Ticker> tickerFactory;

  private @Nullable Long refreshAfterWriteNanos;
  private @Nullable Long expireAfterAccessNanos;
  private @Nullable Long expireAfterWriteNanos;
  private @Nullable Long maximumWeight;
  private @Nullable Long maximumSize;
  private boolean nativeStatistics;

  public CaffeineConfiguration() {
    delegate = new MutableConfiguration<>();
    delegate.setStoreByValue(false);
    schedulerFactory = DISABLED_SCHEDULER;
    tickerFactory = SYSTEM_TICKER;
    executorFactory = COMMON_POOL;
    copierFactory = JAVA_COPIER;
    readOnly = false;
  }

  /** Returns a modifiable copy of the configuration. */
  public CaffeineConfiguration(CompleteConfiguration<K, V> configuration) {
    this(configuration, /* readOnly */ false);
  }

  private CaffeineConfiguration(CompleteConfiguration<K, V> configuration, boolean readOnly) {
    delegate = new MutableConfiguration<>(configuration);
    if (configuration instanceof CaffeineConfiguration<?, ?>) {
      CaffeineConfiguration<K, V> config = (CaffeineConfiguration<K, V>) configuration;
      refreshAfterWriteNanos = config.refreshAfterWriteNanos;
      expireAfterAccessNanos = config.expireAfterAccessNanos;
      expireAfterWriteNanos = config.expireAfterWriteNanos;
      nativeStatistics = config.nativeStatistics;
      schedulerFactory = config.schedulerFactory;
      executorFactory = config.executorFactory;
      expiryFactory = config.expiryFactory;
      copierFactory = config.copierFactory;
      tickerFactory = config.tickerFactory;
      weigherFactory = config.weigherFactory;
      maximumWeight = config.maximumWeight;
      maximumSize = config.maximumSize;
    } else {
      schedulerFactory = DISABLED_SCHEDULER;
      tickerFactory = SYSTEM_TICKER;
      executorFactory = COMMON_POOL;
      copierFactory = JAVA_COPIER;
    }
    this.readOnly = readOnly;
  }

  /** Returns an unmodifiable copy of this configuration. */
  public CaffeineConfiguration<K, V> immutableCopy() {
    return new CaffeineConfiguration<>(this, /* immutable */ true);
  }

  private void checkIfReadOnly() {
    if (readOnly) {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public Class<K> getKeyType() {
    return delegate.getKeyType();
  }

  @Override
  public Class<V> getValueType() {
    return delegate.getValueType();
  }

  /** See {@link MutableConfiguration#setTypes}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setTypes(Class<K> keyType, Class<V> valueType) {
    checkIfReadOnly();
    delegate.setTypes(keyType, valueType);
    return this;
  }

  @Override
  public Iterable<CacheEntryListenerConfiguration<K, V>> getCacheEntryListenerConfigurations() {
    return new UnmodifiableIterable<>(delegate.getCacheEntryListenerConfigurations());
  }

  /** See {@link MutableConfiguration#addCacheEntryListenerConfiguration}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> addCacheEntryListenerConfiguration(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    checkIfReadOnly();
    delegate.addCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
    return this;
  }

  /** See {@link MutableConfiguration#removeCacheEntryListenerConfiguration}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> removeCacheEntryListenerConfiguration(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    checkIfReadOnly();
    delegate.removeCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
    return this;
  }

  @Override
  public @Nullable Factory<CacheLoader<K, V>> getCacheLoaderFactory() {
    return delegate.getCacheLoaderFactory();
  }

  /** See {@link MutableConfiguration#setCacheLoaderFactory}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setCacheLoaderFactory(
      Factory<? extends CacheLoader<K, V>> factory) {
    checkIfReadOnly();
    delegate.setCacheLoaderFactory(factory);
    return this;
  }

  @Override
  public @Nullable Factory<CacheWriter<? super K, ? super V>> getCacheWriterFactory() {
    return delegate.getCacheWriterFactory();
  }

  /** Returns a writer created by the configured factory or null if not set. */
  public @Nullable CacheWriter<K , V> getCacheWriter() {
    var factory = delegate.getCacheWriterFactory();
    if (factory != null) {
      @SuppressWarnings("unchecked")
      CacheWriter<K , V> writer = (CacheWriter<K, V>) factory.create();
      return writer;
    }
    return null;
  }

  /** Returns if the cache writer factory is specified. */
  public boolean hasCacheWriter() {
    return getCacheWriterFactory() != null;
  }

  /** See {@link MutableConfiguration#setCacheWriterFactory}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setCacheWriterFactory(
      Factory<? extends CacheWriter<? super K, ? super V>> factory) {
    checkIfReadOnly();
    delegate.setCacheWriterFactory(factory);
    return this;
  }

  @Override
  public Factory<ExpiryPolicy> getExpiryPolicyFactory() {
    return delegate.getExpiryPolicyFactory();
  }

  /** See {@link MutableConfiguration#setExpiryPolicyFactory}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setExpiryPolicyFactory(
      Factory<? extends ExpiryPolicy> factory) {
    checkIfReadOnly();
    delegate.setExpiryPolicyFactory(factory);
    return this;
  }

  @Override
  public boolean isReadThrough() {
    return delegate.isReadThrough();
  }

  /** See {@link MutableConfiguration#setReadThrough}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setReadThrough(boolean isReadThrough) {
    checkIfReadOnly();
    delegate.setReadThrough(isReadThrough);
    return this;
  }

  @Override
  public boolean isWriteThrough() {
    return delegate.isWriteThrough();
  }

  /** See {@link MutableConfiguration#setWriteThrough}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setWriteThrough(boolean isWriteThrough) {
    checkIfReadOnly();
    delegate.setWriteThrough(isWriteThrough);
    return this;
  }

  @Override
  public boolean isStoreByValue() {
    return delegate.isStoreByValue();
  }

  /** See {@link MutableConfiguration#setStoreByValue}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setStoreByValue(boolean isStoreByValue) {
    checkIfReadOnly();
    delegate.setStoreByValue(isStoreByValue);
    return this;
  }

  /**
   * Checks whether native statistics collection is enabled in this cache.
   * <p>
   * The default value is <code>false</code>.
   *
   * @return true if native statistics collection is enabled
   */
  public boolean isNativeStatisticsEnabled() {
    return nativeStatistics;
  }

  /**
   * Sets whether native statistics gathering is enabled on a cache.
   *
   * @param enabled true to enable native statistics, false to disable.
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setNativeStatisticsEnabled(boolean enabled) {
    checkIfReadOnly();
    this.nativeStatistics = enabled;
    return this;
  }

  @Override
  public boolean isStatisticsEnabled() {
    return delegate.isStatisticsEnabled();
  }

  /** See {@link MutableConfiguration#setStatisticsEnabled}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setStatisticsEnabled(boolean enabled) {
    checkIfReadOnly();
    delegate.setStatisticsEnabled(enabled);
    return this;
  }

  /** See {@link CompleteConfiguration#isManagementEnabled}. */
  @Override
  public boolean isManagementEnabled() {
    return delegate.isManagementEnabled();
  }

  /** See {@link MutableConfiguration#setManagementEnabled}. */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setManagementEnabled(boolean enabled) {
    checkIfReadOnly();
    delegate.setManagementEnabled(enabled);
    return this;
  }

  /**
   * Returns the {@link Factory} for the {@link Copier} to be used for the cache.
   *
   * @return the {@link Factory} for the {@link Copier}
   */
  public Factory<Copier> getCopierFactory() {
    return copierFactory;
  }

  /**
   * Set the {@link Factory} for the {@link Copier}.
   *
   * @param factory the {@link Copier} {@link Factory}
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setCopierFactory(Factory<Copier> factory) {
    checkIfReadOnly();
    copierFactory = requireNonNull(factory);
    return this;
  }

  /**
   * Returns the {@link Factory} for the {@link Scheduler} to be used for the cache.
   *
   * @return the {@link Factory} for the {@link Scheduler}
   */
  public Factory<Scheduler> getSchedulerFactory() {
    return schedulerFactory;
  }

  /**
   * Set the {@link Factory} for the {@link Scheduler}.
   *
   * @param factory the {@link Scheduler} {@link Factory}
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setSchedulerFactory(Factory<Scheduler> factory) {
    checkIfReadOnly();
    schedulerFactory = requireNonNull(factory);
    return this;
  }

  /**
   * Returns the {@link Factory} for the {@link Ticker} to be used for the cache.
   *
   * @return the {@link Factory} for the {@link Ticker}
   */
  public Factory<Ticker> getTickerFactory() {
    return tickerFactory;
  }

  /**
   * Set the {@link Factory} for the {@link Ticker}.
   *
   * @param factory the {@link Ticker} {@link Factory}
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setTickerFactory(Factory<Ticker> factory) {
    checkIfReadOnly();
    tickerFactory = requireNonNull(factory);
    return this;
  }

  /**
   * Returns the {@link Factory} for the {@link Executor} to be used for the cache.
   *
   * @return the {@link Factory} for the {@link Executor}
   */
  public Factory<Executor> getExecutorFactory() {
    return executorFactory;
  }

  /**
   * Set the {@link Factory} for the {@link Executor}.
   *
   * @param factory the {@link Executor} {@link Factory}
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setExecutorFactory(Factory<Executor> factory) {
    checkIfReadOnly();
    executorFactory = requireNonNull(factory);
    return this;
  }

  /**
   * Returns the refresh after write in nanoseconds.
   *
   * @return the duration in nanoseconds
   */
  public OptionalLong getRefreshAfterWrite() {
    return (refreshAfterWriteNanos == null)
        ? OptionalLong.empty()
        : OptionalLong.of(refreshAfterWriteNanos);
  }

  /**
   * Set the refresh after write in nanoseconds.
   *
   * @param refreshAfterWriteNanos the duration in nanoseconds
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setRefreshAfterWrite(OptionalLong refreshAfterWriteNanos) {
    checkIfReadOnly();
    this.refreshAfterWriteNanos = refreshAfterWriteNanos.isPresent()
        ? refreshAfterWriteNanos.getAsLong()
        : null;
    return this;
  }

  /**
   * Returns the expire after write in nanoseconds.
   *
   * @return the duration in nanoseconds
   */
  public OptionalLong getExpireAfterWrite() {
    return (expireAfterWriteNanos == null)
        ? OptionalLong.empty()
        : OptionalLong.of(expireAfterWriteNanos);
  }

  /**
   * Set the expire after write in nanoseconds.
   *
   * @param expireAfterWriteNanos the duration in nanoseconds
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setExpireAfterWrite(OptionalLong expireAfterWriteNanos) {
    checkIfReadOnly();
    this.expireAfterWriteNanos = expireAfterWriteNanos.isPresent()
        ? expireAfterWriteNanos.getAsLong()
        : null;
    return this;
  }

  /**
   * Returns the expire after access in nanoseconds.
   *
   * @return the duration in nanoseconds
   */
  public OptionalLong getExpireAfterAccess() {
    return (expireAfterAccessNanos == null)
        ? OptionalLong.empty()
        : OptionalLong.of(expireAfterAccessNanos);
  }

  /**
   * Set the expire after write in nanoseconds.
   *
   * @param expireAfterAccessNanos the duration in nanoseconds
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setExpireAfterAccess(OptionalLong expireAfterAccessNanos) {
    checkIfReadOnly();
    this.expireAfterAccessNanos = expireAfterAccessNanos.isPresent()
        ? expireAfterAccessNanos.getAsLong()
        : null;
    return this;
  }

  /**
   * Returns the {@link Factory} for the {@link Expiry} to be used for the cache.
   *
   * @return the {@link Factory} for the {@link Expiry}
   */
  public Optional<Factory<Expiry<K, V>>> getExpiryFactory() {
    return Optional.ofNullable(expiryFactory);
  }

  /**
   * Set the {@link Factory} for the {@link Expiry}.
   *
   * @param factory the {@link Expiry} {@link Factory}
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings({"PMD.LinguisticNaming", "unchecked"})
  public CaffeineConfiguration<K, V> setExpiryFactory(
      Optional<Factory<? extends Expiry<K, V>>> factory) {
    checkIfReadOnly();
    expiryFactory = (Factory<Expiry<K, V>>) factory.orElse(null);
    return this;
  }

  /**
   * Returns the maximum size to be used for the cache.
   *
   * @return the maximum size
   */
  public OptionalLong getMaximumSize() {
    return (maximumSize == null)
        ? OptionalLong.empty()
        : OptionalLong.of(maximumSize);
  }

  /**
   * Set the maximum size.
   *
   * @param maximumSize the maximum size
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setMaximumSize(OptionalLong maximumSize) {
    checkIfReadOnly();
    this.maximumSize = maximumSize.isPresent()
        ? maximumSize.getAsLong()
        : null;
    return this;
  }

  /**
   * Returns the maximum weight to be used for the cache.
   *
   * @return the maximum weight
   */
  public OptionalLong getMaximumWeight() {
    return (maximumWeight == null)
        ? OptionalLong.empty()
        : OptionalLong.of(maximumWeight);
  }

  /**
   * Set the maximum weight.
   *
   * @param maximumWeight the maximum weighted size
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings("PMD.LinguisticNaming")
  public CaffeineConfiguration<K, V> setMaximumWeight(OptionalLong maximumWeight) {
    checkIfReadOnly();
    this.maximumWeight = maximumWeight.isPresent()
        ? maximumWeight.getAsLong()
        : null;
    return this;
  }

  /**
   * Returns the {@link Factory} for the {@link Weigher} to be used for the cache.
   *
   * @return the {@link Factory} for the {@link Weigher}
   */
  public Optional<Factory<Weigher<K, V>>> getWeigherFactory() {
    return Optional.ofNullable(weigherFactory);
  }

  /**
   * Set the {@link Factory} for the {@link Weigher}.
   *
   * @param factory the {@link Weigher} {@link Factory}
   * @return the {@link CaffeineConfiguration} to permit fluent-style method calls
   */
  @CanIgnoreReturnValue
  @SuppressWarnings({"PMD.LinguisticNaming", "unchecked"})
  public CaffeineConfiguration<K, V> setWeigherFactory(
      Optional<Factory<? extends Weigher<K, V>>> factory) {
    checkIfReadOnly();
    weigherFactory = (Factory<Weigher<K, V>>) factory.orElse(null);
    return this;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof CaffeineConfiguration<?, ?>)) {
      return false;
    }
    CaffeineConfiguration<?, ?> config = (CaffeineConfiguration<?, ?>) o;
    return Objects.equals(refreshAfterWriteNanos, config.refreshAfterWriteNanos)
        && Objects.equals(expireAfterAccessNanos, config.expireAfterAccessNanos)
        && Objects.equals(expireAfterWriteNanos, config.expireAfterWriteNanos)
        && Objects.equals(executorFactory, config.executorFactory)
        && Objects.equals(copierFactory, config.copierFactory)
        && Objects.equals(tickerFactory, config.tickerFactory)
        && Objects.equals(weigherFactory, config.weigherFactory)
        && Objects.equals(maximumWeight, config.maximumWeight)
        && Objects.equals(maximumSize, config.maximumSize)
        && delegate.equals(config.delegate);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  private static final class UnmodifiableIterable<E> implements Iterable<E> {
    private final Iterable<E> delegate;

    private UnmodifiableIterable(Iterable<E> delegate) {
      this.delegate = delegate;
    }
    @Override public Iterator<E> iterator() {
      var iterator = delegate.iterator();
      return new Iterator<E>() {
        @Override public boolean hasNext() {
          return iterator.hasNext();
        }
        @Override public E next() {
          return iterator.next();
        }
      };
    }
    @Override public Spliterator<E> spliterator() {
      return delegate.spliterator();
    }
    @Override public String toString() {
      return delegate.toString();
    }
  }
}
