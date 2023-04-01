/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache.integration;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;

import org.mockito.Mockito;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.collect.Maps;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheLoaderTest extends AbstractJCacheTest {
  private CacheLoader<Integer, Integer> cacheLoader;
  private ExpiryPolicy expiry;

  @Test
  public void load() {
    when(cacheLoader.load(any())).thenReturn(-1);
    assertThat(jcacheLoading.get(1)).isEqualTo(-1);
  }

  @Test
  public void load_null() {
    when(cacheLoader.load(any())).thenReturn(null);
    assertThat(jcacheLoading.get(1)).isNull();
  }

  @Test(dataProvider = "throwables")
  public void load_failure(Throwable throwable) {
    when(cacheLoader.load(any())).thenThrow(throwable);
    var e = assertThrows(CacheLoaderException.class, () -> jcacheLoading.get(1));
    if (e != throwable) {
      assertThat(e).hasCauseThat().isSameInstanceAs(throwable);
    }
  }

  @Test
  public void load_failure_expiry() {
    when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
    when(cacheLoader.load(any())).thenReturn(-1);
    var e = assertThrows(CacheLoaderException.class, () -> jcacheLoading.get(1));
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void loadAll() {
    when(cacheLoader.loadAll(anyIterable())).then(answer -> {
      Iterable<Integer> keys = answer.getArgument(0);
      return Maps.toMap(keys, key -> -key);
    });
    var result = jcacheLoading.getAll(Set.of(1, 2, 3));
    assertThat(result).containsExactly(1, -1, 2, -2, 3, -3);
  }

  @Test
  public void loadAll_null() {
    when(cacheLoader.loadAll(anyIterable())).thenReturn(null);
    assertThrows(CacheLoaderException.class, () -> jcacheLoading.getAll(Set.of(1, 2, 3)));
  }

  @Test
  public void loadAll_nullMapping() {
    when(cacheLoader.loadAll(anyIterable())).thenReturn(Collections.singletonMap(1, null));
    var result = jcacheLoading.getAll(Set.of(1, 2, 3));
    assertThat(result).isEmpty();
  }

  @Test(dataProvider = "throwables")
  public void loadAll_failure(Throwable throwable) {
    when(cacheLoader.loadAll(any())).thenThrow(throwable);
    var e = assertThrows(CacheLoaderException.class, () -> jcacheLoading.getAll(Set.of(1, 2, 3)));
    if (e != throwable) {
      assertThat(e).hasCauseThat().isSameInstanceAs(throwable);
    }
  }

  @Test
  public void loadAll_failure_expiry() {
    when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
    when(cacheLoader.loadAll(anyIterable())).thenReturn(Map.of(1, 1, 2, 2));
    var e = assertThrows(CacheLoaderException.class, () -> jcacheLoading.getAll(Set.of(1, 2, 3)));
    assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
  }

  @DataProvider(name = "throwables")
  Object[] providesThrowables() {
    return new Object[] { new IllegalStateException(), new CacheLoaderException() };
  }

  @Override protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    expiry = Mockito.mock(answer -> Duration.ZERO);
    cacheLoader = Mockito.mock();

    var configuration = new CaffeineConfiguration<Integer, Integer>();
    configuration.setExpiryPolicyFactory(() -> expiry);
    configuration.setStatisticsEnabled(true);
    return configuration;
  }

  @Override protected CacheLoader<Integer, Integer> getCacheLoader() {
    return cacheLoader;
  }
}
