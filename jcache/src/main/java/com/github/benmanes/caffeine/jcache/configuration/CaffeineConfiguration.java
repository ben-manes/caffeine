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
  private static final long UNSET = -1;

  private final MutableConfiguration<K, V> delegate;

  private Factory<CopyStrategy> copyStrategyFactory;
  private Factory<Weigher<K, V>> weigherFactory;
  private long maximumWeight = UNSET;
  private long maximumSize = UNSET;

  public CaffeineConfiguration() {
    delegate = new MutableConfiguration<>();
  }

  public CaffeineConfiguration(CompleteConfiguration<K, V> configuration) {
    if (configuration instanceof CaffeineConfiguration<?, ?>) {
      copyStrategyFactory = ((CaffeineConfiguration<K, V>) configuration).getCopyStrategyFactory();
    }
    delegate = new MutableConfiguration<>(configuration);
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

  /**
   * Set the maximum weight.
   *
   * @param maximumWeight the maximum weighted size
   */
  public void setMaximimWeight(Long maximumWeight) {
    this.maximumWeight = maximumWeight;
  }

  /**
   * Returns the maximum weight to be used for the cache.
   *
   * @return the maximum weight or null if not set
   */
  public Long getMaximimWeight() {
    return maximumWeight;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof CaffeineConfiguration<?, ?>)) {
      return false;
    }
    CaffeineConfiguration<?, ?> config = (CaffeineConfiguration<?, ?>) o;
    return delegate.equals(config.delegate)
        && Objects.equals(copyStrategyFactory, config.copyStrategyFactory)
        && Objects.equals(weigherFactory, config.weigherFactory)
        && (maximumWeight == config.maximumWeight)
        && (maximumSize == config.maximumSize);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }
}
