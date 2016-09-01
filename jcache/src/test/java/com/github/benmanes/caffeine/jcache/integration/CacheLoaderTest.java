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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.AbstractJCacheTest;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheLoaderTest extends AbstractJCacheTest {
  private Supplier<Map<Integer, Integer>> loadAllSupplier;
  private Supplier<Integer> loadSupplier;

  @BeforeMethod
  public void beforeMethod() {
    loadSupplier = () -> { throw new UnsupportedOperationException(); };
    loadAllSupplier = () -> { throw new UnsupportedOperationException(); };
  }

  @Test
  public void load() {
    loadSupplier = () -> -1;
    assertThat(jcacheLoading.get(1), is(-1));
  }

  @Test
  public void load_null() {
    loadSupplier = () -> null;
    assertThat(jcacheLoading.get(1), is(nullValue()));
  }

  @Test
  public void load_failure() {
    try {
      loadSupplier = () -> { throw new IllegalStateException(); };
      jcacheLoading.get(1);
      Assert.fail();
    } catch (CacheLoaderException e) {
      assertThat(e.getCause(), instanceOf(IllegalStateException.class));
    }
  }

  @Test
  public void loadAll() {
    loadAllSupplier = () -> ImmutableMap.of(1, -1, 2, -2, 3, -3);
    Map<Integer, Integer> result = jcacheLoading.getAll(ImmutableSet.of(1, 2, 3));
    assertThat(result, is(equalTo(loadAllSupplier.get())));
  }

  @Test(expectedExceptions = CacheLoaderException.class)
  public void loadAll_null() {
    loadAllSupplier = () -> null;
    jcacheLoading.getAll(ImmutableSet.of(1, 2, 3));
  }

  @Test
  public void loadAll_nullMapping() {
    loadAllSupplier = () -> Collections.singletonMap(1, null);
    Map<Integer, Integer> result = jcacheLoading.getAll(ImmutableSet.of(1, 2, 3));
    assertThat(result, is(anEmptyMap()));
  }

  @Override protected CaffeineConfiguration<Integer, Integer> getConfiguration() {
    return new CaffeineConfiguration<>();
  }

  @Override protected CacheLoader<Integer, Integer> getCacheLoader() {
    return new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        return loadSupplier.get();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        return loadAllSupplier.get();
      }
    };
  }
}
