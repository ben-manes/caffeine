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
package com.github.benmanes.caffeine.jcache.management;

import static java.util.Objects.requireNonNull;

import javax.cache.Cache;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.management.CacheMXBean;

/**
 * The Caffeine JCache management bean.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheMXBean implements CacheMXBean {
  private final Cache<?, ?> cache;

  public JCacheMXBean(Cache<?, ?> cache) {
    this.cache = requireNonNull(cache);
  }

  @Override
  public String getKeyType() {
    return configuration().getKeyType().getName();
  }

  @Override
  public String getValueType() {
    return configuration().getValueType().getName();
  }

  @Override
  public boolean isReadThrough() {
    return configuration().isReadThrough();
  }

  @Override
  public boolean isWriteThrough() {
    return configuration().isWriteThrough();
  }

  @Override
  public boolean isStoreByValue() {
    return configuration().isStoreByValue();
  }

  @Override
  public boolean isStatisticsEnabled() {
    return configuration().isStatisticsEnabled();
  }

  @Override
  public boolean isManagementEnabled() {
    return configuration().isManagementEnabled();
  }

  @SuppressWarnings("unchecked")
  private CompleteConfiguration<?, ?> configuration() {
    return cache.getConfiguration(CompleteConfiguration.class);
  }
}
