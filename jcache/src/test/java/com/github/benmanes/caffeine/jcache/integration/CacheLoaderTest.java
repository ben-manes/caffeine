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

import static com.github.benmanes.caffeine.jcache.JCacheFixture.nullRef;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.jcache.JCacheFixture;
import com.google.common.collect.Maps;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CacheLoaderTest {

  private static JCacheFixture jcacheFixture(
      ExpiryPolicy expiry, CacheLoader<Integer, Integer> cacheLoader) {
    return JCacheFixture.builder()
        .loading(config -> {
          config.setCacheLoaderFactory(() -> cacheLoader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setStatisticsEnabled(true);
          config.setReadThrough(true);
        }).build();
  }

  @Test
  void load() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.load(any())).thenReturn(-1);
      assertThat(fixture.jcacheLoading().get(1)).isEqualTo(-1);
    }
  }

  @Test
  void load_null() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.load(any())).thenReturn(nullRef());
      assertThat(fixture.jcacheLoading().get(1)).isNull();
    }
  }

  @ParameterizedTest @MethodSource("throwables")
  void load_failure(Throwable throwable) {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.load(any())).thenThrow(throwable);
      var e = assertThrows(CacheLoaderException.class, () -> fixture.jcacheLoading().get(1));
      if (e != throwable) {
        assertThat(e).hasCauseThat().isSameInstanceAs(throwable);
      }
    }
  }

  @Test
  void load_failure_expiry() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
      when(cacheLoader.load(any())).thenReturn(-1);
      var e = assertThrows(CacheLoaderException.class, () -> fixture.jcacheLoading().get(1));
      assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void loadAll() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.loadAll(anyIterable())).then(answer -> {
        Iterable<Integer> keys = answer.getArgument(0);
        return Maps.toMap(keys, key -> -key);
      });
      var result = fixture.jcacheLoading().getAll(Set.of(1, 2, 3));
      assertThat(result).containsExactly(1, -1, 2, -2, 3, -3);
    }
  }

  @Test
  void loadAll_null() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.loadAll(anyIterable())).thenReturn(null);
      assertThrows(CacheLoaderException.class, () ->
          fixture.jcacheLoading().getAll(Set.of(1, 2, 3)));
    }
  }

  @Test
  @SuppressWarnings("NullableProblems")
  void loadAll_nullMapping() {
    var mappings = new HashMap<@Nullable Integer, @Nullable Integer>();
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    mappings.put(1, null);
    mappings.put(null, 2);
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.loadAll(anyIterable())).thenReturn(mappings);
      var result = fixture.jcacheLoading().getAll(Set.of(1, 2, 3));
      assertThat(result).isEmpty();
    }
  }

  @ParameterizedTest @MethodSource("throwables")
  void loadAll_failure(Throwable throwable) {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.loadAll(any())).thenThrow(throwable);
      var e = assertThrows(CacheLoaderException.class, () ->
          fixture.jcacheLoading().getAll(Set.of(1, 2, 3)));
      if (e != throwable) {
        assertThat(e).hasCauseThat().isSameInstanceAs(throwable);
      }
    }
  }

  @Test
  void loadAll_failure_expiry() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
      when(cacheLoader.loadAll(anyIterable())).thenReturn(Map.of(1, 1, 2, 2));
      var e = assertThrows(CacheLoaderException.class, () ->
          fixture.jcacheLoading().getAll(Set.of(1, 2, 3)));
      assertThat(e).hasCauseThat().isInstanceOf(IllegalStateException.class);
    }
  }

  @MethodSource
  static Stream<Exception> throwables() {
    return Stream.of(new IllegalStateException(), new CacheLoaderException());
  }
}
