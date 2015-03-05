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

import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.configuration.Factory;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.event.CacheEntryListener;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheWriter;

import com.github.benmanes.caffeine.jcache.copy.CopyStrategy;
import com.typesafe.config.Config;

/**
 * A JCache configuration with Caffeine specific settings.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineConfiguration<K, V> implements CompleteConfiguration<K, V> {
  private static final long serialVersionUID = 1L;

  private final MutableConfiguration<K, V> delegate;

  private Factory<CopyStrategy> copyStrategyFactory;

  public CaffeineConfiguration() {
    delegate = new MutableConfiguration<>();
  }

  public CaffeineConfiguration(CompleteConfiguration<K, V> configuration) {
    if (configuration instanceof CaffeineConfiguration<?, ?>) {
      copyStrategyFactory = ((CaffeineConfiguration<K, V>) configuration).getCopyStrategyFactory();
    }
    delegate = new MutableConfiguration<>(configuration);
  }

  /**
   * Retrieves the cache's settings from the configuration resource.
   *
   * @param config the configuration resource
   * @param <K> the type of keys maintained the cache
   * @param <V> the type of cached values
   * @return the configuration for the cache
   */
  public static <K, V> CaffeineConfiguration<K, V> from(Config config) {
    CaffeineConfiguration<K, V> configuration = new CaffeineConfiguration<>();
    configuration.setCopyStrategyFactory(FactoryBuilder.factoryOf(
        config.getString("storeByValue.strategy")));
    return configuration;
  }

  public Factory<CopyStrategy> getCopyStrategyFactory() {
    return copyStrategyFactory;
  }

  public CaffeineConfiguration<K, V> setCopyStrategyFactory(Factory<CopyStrategy> factory) {
    copyStrategyFactory = factory;
    return this;
  }

  @Override
  public Class<K> getKeyType() {
    return delegate.getKeyType();
  }

  @Override
  public Class<V> getValueType() {
    return delegate.getValueType();
  }

  /**
   * Sets the expected type of keys and values for a {@link Cache} configured with this
   * {@link Configuration}. Setting both to <code>Object.class</code> means type-safety checks are
   * not required.
   * <p>
   * This is used by {@link CacheManager} to ensure that the key and value types are the same as
   * those configured for the {@link Cache} prior to returning a requested cache from this method.
   * <p>
   * Implementations may further perform type checking on mutative cache operations and throw a
   * {@link ClassCastException} if these checks fail.
   *
   * @param keyType the expected key type
   * @param valueType the expected value type
   * @throws NullPointerException should the key or value type be null
   * @see CacheManager#getCache(String, Class, Class)
   */
  public void setTypes(Class<K> keyType, Class<V> valueType) {
    delegate.setTypes(keyType, valueType);
  }

  @Override
  public boolean isStoreByValue() {
    return delegate.isStoreByValue();
  }

  /**
   * Set if a configured cache should use store-by-value or store-by-reference semantics.
   *
   * @param isStoreByValue {@code true} if store-by-value is required, otherwise store-by-reference
   */
  public void setStoreByValue(boolean isStoreByValue) {
    delegate.setStoreByValue(isStoreByValue);
  }

  @Override
  public boolean isReadThrough() {
    return delegate.isReadThrough();
  }

  @Override
  public boolean isWriteThrough() {
    return delegate.isWriteThrough();
  }

  @Override
  public boolean isStatisticsEnabled() {
    return delegate.isStatisticsEnabled();
  }

  /**
   * Sets whether statistics gathering is enabled on a cache.
   * <p>
   * Statistics may be enabled or disabled at runtime via
   * {@link CacheManager#enableStatistics(String, boolean)}.
   *
   * @param enabled true to enable statistics, false to disable
   */
  public void setStatisticsEnabled(boolean enabled) {
    delegate.setStatisticsEnabled(enabled);
  }

  @Override
  public boolean isManagementEnabled() {
    return delegate.isManagementEnabled();
  }

  /**
   * Sets whether management is enabled on a cache.
   * <p>
   * Management may be enabled or disabled at runtime via
   * {@link CacheManager#enableManagement(String, boolean)}.
   *
   * @param enabled true to enable statistics, false to disable
   */
  public void setManagementEnabled(boolean enabled) {
    delegate.setManagementEnabled(enabled);
  }

  @Override
  public Iterable<CacheEntryListenerConfiguration<K, V>> getCacheEntryListenerConfigurations() {
    return delegate.getCacheEntryListenerConfigurations();
  }

  /**
   * Add a configuration for a {@link CacheEntryListener}.
   *
   * @param cacheEntryListenerConfiguration the {@link CacheEntryListenerConfiguration}
   * @throws IllegalArgumentException is the same CacheEntryListenerConfiguration is used more than
   *         once
   */
  public void addCacheEntryListenerConfiguration(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    delegate.addCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
  }

  /**
   * Remove a configuration for a {@link CacheEntryListener}.
   *
   * @param cacheEntryListenerConfiguration the {@link CacheEntryListenerConfiguration} to remove
   */
  public void removeCacheEntryListenerConfiguration(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    delegate.removeCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
  }

  @Override
  public Factory<CacheLoader<K, V>> getCacheLoaderFactory() {
    return delegate.getCacheLoaderFactory();
  }

  @Override
  public Factory<CacheWriter<? super K, ? super V>> getCacheWriterFactory() {
    return delegate.getCacheWriterFactory();
  }

  public @Nullable CacheWriter<K , V> getCacheWriter() {
    if (hasCacheWriter()) {
      @SuppressWarnings("unchecked")
      CacheWriter<K , V> writer = (CacheWriter<K, V>) getCacheWriterFactory().create();
      return writer;
    }
    return null;
  }

  public boolean hasCacheWriter() {
    return getCacheWriterFactory() != null;
  }

  @Override
  public Factory<ExpiryPolicy> getExpiryPolicyFactory() {
    return delegate.getExpiryPolicyFactory();
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
        && copyStrategyFactory.equals(config.copyStrategyFactory);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }
}
