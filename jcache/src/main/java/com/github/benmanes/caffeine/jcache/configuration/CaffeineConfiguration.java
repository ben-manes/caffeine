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

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
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
  }

  public CaffeineConfiguration(CompleteConfiguration<K, V> configuration) {
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
  public void setTypes(Class<K> keyType, Class<V> valueType) {
    delegate.setTypes(keyType, valueType);
  }

  @Override
  public Iterable<CacheEntryListenerConfiguration<K, V>> getCacheEntryListenerConfigurations() {
    return delegate.getCacheEntryListenerConfigurations();
  }

  /** See {@link MutableConfiguration#addCacheEntryListenerConfiguration}. */
  public void addCacheEntryListenerConfiguration(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    delegate.addCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
  }

  /** See {@link MutableConfiguration#removeCacheEntryListenerConfiguration}. */
  public void removeCacheEntryListenerConfiguration(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    delegate.removeCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
  }

  @Override
  public Factory<CacheLoader<K, V>> getCacheLoaderFactory() {
    return delegate.getCacheLoaderFactory();
  }

  /** See {@link MutableConfiguration#setCacheLoaderFactory}. */
  public void setCacheLoaderFactory(Factory<? extends CacheLoader<K, V>> factory) {
    delegate.setCacheLoaderFactory(factory);
  }

  @Override
  public Factory<CacheWriter<? super K, ? super V>> getCacheWriterFactory() {
    return delegate.getCacheWriterFactory();
  }

  /** @return a writer created by the configured factory or null if not set. */
  public @Nullable CacheWriter<K , V> getCacheWriter() {
    if (hasCacheWriter()) {
      @SuppressWarnings("unchecked")
      CacheWriter<K , V> writer = (CacheWriter<K, V>) getCacheWriterFactory().create();
      return writer;
    }
    return null;
  }

  /** @return if the cache writer factory is specified. */
  public boolean hasCacheWriter() {
    return getCacheWriterFactory() != null;
  }

  /** See {@link MutableConfiguration#setCacheWriterFactory}. */
  public void setCacheWriterFactory(Factory<? extends CacheWriter<? super K, ? super V>> factory) {
    delegate.setCacheWriterFactory(factory);
  }

  @Override
  public Factory<ExpiryPolicy> getExpiryPolicyFactory() {
    return delegate.getExpiryPolicyFactory();
  }

  /** See {@link MutableConfiguration#setExpiryPolicyFactory}. */
  public void setExpiryPolicyFactory(Factory<? extends ExpiryPolicy> factory) {
    delegate.setExpiryPolicyFactory(factory);
  }

  @Override
  public boolean isReadThrough() {
    return delegate.isReadThrough();
  }

  /** See {@link MutableConfiguration#setReadThrough}. */
  public void setReadThrough(boolean isReadThrough) {
    delegate.setReadThrough(isReadThrough);
  }

  @Override
  public boolean isWriteThrough() {
    return delegate.isWriteThrough();
  }

  /** See {@link MutableConfiguration#setWriteThrough}. */
  public void setWriteThrough(boolean isWriteThrough) {
    delegate.setWriteThrough(isWriteThrough);
  }

  @Override
  public boolean isStoreByValue() {
    return delegate.isStoreByValue();
  }

  /** See {@link MutableConfiguration#setStoreByValue}. */
  public void setStoreByValue(boolean isStoreByValue) {
    delegate.setStoreByValue(isStoreByValue);
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
   */
  public void setNativeStatisticsEnabled(boolean enabled) {
    this.nativeStatistics = enabled;
  }

  @Override
  public boolean isStatisticsEnabled() {
    return delegate.isStatisticsEnabled();
  }

  /** See {@link MutableConfiguration#setStatisticsEnabled}. */
  public void setStatisticsEnabled(boolean enabled) {
    delegate.setStatisticsEnabled(enabled);
  }

  /** See {@link MutableConfiguration#isManagementEnabled}. */
  @Override
  public boolean isManagementEnabled() {
    return delegate.isManagementEnabled();
  }

  /** See {@link MutableConfiguration#setManagementEnabled}. */
  public void setManagementEnabled(boolean enabled) {
    delegate.setManagementEnabled(enabled);
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
   */
  public void setCopierFactory(Factory<Copier> factory) {
    copierFactory = requireNonNull(factory);
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
   */
  public void setSchedulerFactory(Factory<Scheduler> factory) {
    schedulerFactory = requireNonNull(factory);
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
   */
  public void setTickerFactory(Factory<Ticker> factory) {
    tickerFactory = requireNonNull(factory);
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
   */
  public void setExecutorFactory(Factory<Executor> factory) {
    executorFactory = requireNonNull(factory);
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
   */
  public void setRefreshAfterWrite(OptionalLong refreshAfterWriteNanos) {
    this.refreshAfterWriteNanos = refreshAfterWriteNanos.isPresent()
        ? refreshAfterWriteNanos.getAsLong()
        : null;
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
   */
  public void setExpireAfterWrite(OptionalLong expireAfterWriteNanos) {
    this.expireAfterWriteNanos = expireAfterWriteNanos.isPresent()
        ? expireAfterWriteNanos.getAsLong()
        : null;
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
   */
  public void setExpireAfterAccess(OptionalLong expireAfterAccessNanos) {
    this.expireAfterAccessNanos = expireAfterAccessNanos.isPresent()
        ? expireAfterAccessNanos.getAsLong()
        : null;
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
   */
  @SuppressWarnings("unchecked")
  public void setExpiryFactory(Optional<Factory<? extends Expiry<K, V>>> factory) {
    expiryFactory = (Factory<Expiry<K, V>>) factory.orElse(null);
  }

  /**
   * Set the maximum size.
   *
   * @param maximumSize the maximum size
   */
  public void setMaximumSize(OptionalLong maximumSize) {
    this.maximumSize = maximumSize.isPresent()
        ? maximumSize.getAsLong()
        : null;
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
   * Set the maximum weight.
   *
   * @param maximumWeight the maximum weighted size
   */
  public void setMaximumWeight(OptionalLong maximumWeight) {
    this.maximumWeight = maximumWeight.isPresent()
        ? maximumWeight.getAsLong()
        : null;
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
   */
  @SuppressWarnings("unchecked")
  public void setWeigherFactory(Optional<Factory<? extends Weigher<K, V>>> factory) {
    weigherFactory = (Factory<Weigher<K, V>>) factory.orElse(null);
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
}
