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

import java.util.Objects;
import java.util.OptionalLong;

import javax.annotation.Nullable;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheWriter;

import com.github.benmanes.caffeine.cache.Weigher;
import com.github.benmanes.caffeine.jcache.copy.CopyStrategy;

/**
 * A JCache configuration with Caffeine specific settings.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineConfiguration<K, V> implements CompleteConfiguration<K, V> {
  private static final long serialVersionUID = 1L;

  private Factory<CopyStrategy> copyStrategyFactory;
  private Factory<Weigher<K, V>> weigherFactory;
  private MutableConfiguration<K, V> delegate;
  private Long expireAfterAccessNanos;
  private Long expireAfterWriteNanos;
  private Long maximumWeight;
  private Long maximumSize;

  public CaffeineConfiguration() {
    delegate = new MutableConfiguration<>();
  }

  public CaffeineConfiguration(CompleteConfiguration<K, V> configuration) {
    this();
    delegate = new MutableConfiguration<>(configuration);
    if (configuration instanceof CaffeineConfiguration<?, ?>) {
      copyStrategyFactory = ((CaffeineConfiguration<K, V>) configuration).getCopyStrategyFactory();
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
   * Returns the {@link Factory} for the {@link CopyStrategy} to be used for the cache.
   *
   * @return the {@link Factory} for the {@link CopyStrategy}
   */
  public Factory<CopyStrategy> getCopyStrategyFactory() {
    return copyStrategyFactory;
  }

  /**
   * Set the {@link Factory} for the {@link CopyStrategy}.
   *
   * @param factory the {@link CopyStrategy} {@link Factory}
   */
  public void setCopyStrategyFactory(Factory<CopyStrategy> factory) {
    copyStrategyFactory = factory;
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
   * @param expireAfterAccessNanos the duration in nanoseconds
   */
  public void setExpireAfterAccess(OptionalLong expireAfterAccessNanos) {
    this.expireAfterAccessNanos = expireAfterAccessNanos.isPresent()
        ? expireAfterAccessNanos.getAsLong()
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
   * Set the maximum weight.
   *
   * @param maximumSize the maximum size
   */
  public void setMaximumSize(OptionalLong maximumSize) {
    this.maximumSize = maximumSize.isPresent()
        ? maximumSize.getAsLong()
        : null;
  }

  /**
   * Returns the maximum weight to be used for the cache.
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
  public Factory<Weigher<K, V>> getWeigherFactory() {
    return weigherFactory;
  }

  /**
   * Set the {@link Factory} for the {@link CopyStrategy}.
   *
   * @param factory the {@link CopyStrategy} {@link Factory}
   */
  public void setWeigherFactory(Factory<Weigher<K, V>> factory) {
    weigherFactory = factory;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof CaffeineConfiguration<?, ?>)) {
      return false;
    }
    CaffeineConfiguration<?, ?> config = (CaffeineConfiguration<?, ?>) o;
    return Objects.equals(expireAfterAccessNanos, config.expireAfterAccessNanos)
        && Objects.equals(expireAfterWriteNanos, config.expireAfterWriteNanos)
        && Objects.equals(copyStrategyFactory, config.copyStrategyFactory)
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
