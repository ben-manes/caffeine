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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.expiry.CreatedExpiryPolicy;
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
import com.google.common.util.concurrent.MoreExecutors;

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
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
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

  @Test
  void load_null_subtractsLoaderTimeFromGetTime() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      // Simulate a slow loader by advancing the cache's ticker inside load().
      when(cacheLoader.load(any())).thenAnswer(invocation -> {
        fixture.ticker().advance(java.time.Duration.ofMillis(100));
        return nullRef();
      });

      assertThat(fixture.jcacheLoading().get(1)).isNull();

      // recordGetTime is documented as excluding loader time. Pre-fix, the null-result
      // early return skipped the subtraction; the 100ms loader stayed attributed to
      // average get-time (~100_000 microseconds). Post-fix the loader's elapsed time
      // is subtracted regardless of the loader's result.
      assertThat(JCacheFixture.getStatistics(fixture.jcacheLoading())
          .getAverageGetTime()).isLessThan(50_000f);
    }
  }

  @Test
  void get_loaderThrows_recordsMiss() {
    // A read-through get whose loader throws still records the miss (the find already failed before
    // the load ran) -- matching the loading getAll, the RI, and core
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    when(cacheLoader.load(any())).thenThrow(new IllegalStateException("boom"));
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      assertThrows(CacheLoaderException.class, () -> fixture.jcacheLoading().get(1));
      assertThat(JCacheFixture.getStatistics(fixture.jcacheLoading()).getCacheMisses()).isEqualTo(1L);
    }
  }

  @Test
  void refresh_publishesUpdatedNotCreated() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.load(1)).thenReturn(-1, -2);

    var created = new AtomicInteger();
    var updated = new AtomicInteger();
    CacheEntryCreatedListener<Integer, Integer> createdListener =
        events -> events.forEach(event -> created.incrementAndGet());
    CacheEntryUpdatedListener<Integer, Integer> updatedListener =
        events -> events.forEach(event -> updated.incrementAndGet());

    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setReadThrough(true);
          config.setCacheLoaderFactory(() -> loader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setRefreshAfterWrite(OptionalLong.of(TimeUnit.MINUTES.toNanos(1)));
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              () -> createdListener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              () -> updatedListener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        }).build();
        var cache = fixture.jcacheLoading()) {
      assertThat(cache.get(1)).isEqualTo(-1);
      fixture.ticker().advance(java.time.Duration.ofMinutes(2));

      // Trigger the refresh, which runs inline via directExecutor.
      assertThat(cache.get(1)).isNotNull();

      // The refresh reloads an existing entry, so it publishes UPDATED, not a second CREATED.
      assertThat(created.get()).isEqualTo(1);
      assertThat(updated.get()).isEqualTo(1);
    }
  }

  @Test
  void reload_nullValue() {
    // A refresh whose reload yields null drops the entry rather than storing null, and publishes
    // REMOVED (symmetric with the UPDATED/EXPIRED a non-null reload fires).
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.load(1)).thenReturn(-1, (Integer) null);

    var removed = new AtomicInteger();
    CacheEntryRemovedListener<Integer, Integer> removedListener =
        events -> events.forEach(event -> removed.incrementAndGet());

    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setReadThrough(true);
          config.setCacheLoaderFactory(() -> loader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setRefreshAfterWrite(OptionalLong.of(TimeUnit.MINUTES.toNanos(1)));
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              () -> removedListener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        }).build();
        var cache = fixture.jcacheLoading()) {
      assertThat(cache.get(1)).isEqualTo(-1);
      fixture.ticker().advance(java.time.Duration.ofMinutes(2));

      // Trigger the refresh; the reload's null result drops the entry and fires REMOVED.
      assertThat(cache.get(1)).isEqualTo(-1);
      verify(loader, Mockito.times(2)).load(1);
      assertThat(cache.containsKey(1)).isFalse();
      assertThat(removed.get()).isEqualTo(1);
    }
  }

  @Test
  void reload_zeroExpiry_publishesExpired() {
    // A reload whose update expiry is ZERO expires the reloaded value in place of updating it.
    ExpiryPolicy expiry = Mockito.mock();
    when(expiry.getExpiryForCreation()).thenReturn(Duration.ETERNAL);
    when(expiry.getExpiryForUpdate()).thenReturn(Duration.ZERO);
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.load(1)).thenReturn(-1, -2);

    var expired = new AtomicInteger();
    CacheEntryExpiredListener<Integer, Integer> expiredListener =
        events -> events.forEach(event -> expired.incrementAndGet());

    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setReadThrough(true);
          config.setCacheLoaderFactory(() -> loader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setRefreshAfterWrite(OptionalLong.of(TimeUnit.MINUTES.toNanos(1)));
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              () -> expiredListener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        }).build();
        var cache = fixture.jcacheLoading()) {
      assertThat(cache.get(1)).isEqualTo(-1);
      fixture.ticker().advance(java.time.Duration.ofMinutes(2));

      // Trigger the refresh; the ZERO update expiry publishes EXPIRED and drops the entry.
      assertThat(cache.get(1)).isEqualTo(-1);
      verify(loader, Mockito.times(2)).load(1);
      assertThat(expired.get()).isEqualTo(1);
      assertThat(cache.containsKey(1)).isFalse();
    }
  }

  @Test
  void reload_nullUpdateExpiry_keepsExpiration() {
    // getExpiryForUpdate() returning null (the CreatedExpiryPolicy/AccessedExpiryPolicy default)
    // means "no change" — a refresh reload must keep the entry's existing expiration rather than
    // make it eternal. A long native expireAfterWrite keeps the jcache-expired entry present.
    var expiry = new CreatedExpiryPolicy(new Duration(TimeUnit.MINUTES, 2));
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.load(1)).thenReturn(-1, -2);

    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setReadThrough(true);
          config.setCacheLoaderFactory(() -> loader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setExpireAfterWrite(OptionalLong.of(TimeUnit.HOURS.toNanos(1)));
          config.setRefreshAfterWrite(OptionalLong.of(TimeUnit.MINUTES.toNanos(1)));
        }).build();
        var cache = fixture.jcacheLoading()) {
      // t=0: created with a 2-minute expiry.
      assertThat(cache.get(1)).isEqualTo(-1);

      // t=90s: past refreshAfterWrite(1m) so the reload runs inline; getExpiryForUpdate()==null
      // must keep the original expiry (t=2m), not extend it to eternal.
      fixture.ticker().advance(java.time.Duration.ofSeconds(90));
      assertThat(cache.get(1)).isNotNull();

      // t=125s: past the original 2-minute expiry but before the reload re-arms refresh (t=150s).
      fixture.ticker().advance(java.time.Duration.ofSeconds(35));
      assertThat(cache.containsKey(1)).isFalse();
    }
  }

  @Test
  void reload_updateExpiryFailure_keepsExpiration() {
    // A throwing getExpiryForUpdate uses the implementation default of leaving the expiration
    // unchanged (matching the put path), not making the entry eternal.
    ExpiryPolicy expiry = Mockito.mock();
    when(expiry.getExpiryForCreation()).thenReturn(new Duration(TimeUnit.MINUTES, 2));
    when(expiry.getExpiryForUpdate()).thenThrow(IllegalStateException.class);
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.load(1)).thenReturn(-1, -2);

    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setReadThrough(true);
          config.setCacheLoaderFactory(() -> loader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setExpireAfterWrite(OptionalLong.of(TimeUnit.HOURS.toNanos(1)));
          config.setRefreshAfterWrite(OptionalLong.of(TimeUnit.MINUTES.toNanos(1)));
        }).build();
        var cache = fixture.jcacheLoading()) {
      assertThat(cache.get(1)).isEqualTo(-1);

      fixture.ticker().advance(java.time.Duration.ofSeconds(90));
      assertThat(cache.get(1)).isNotNull();

      fixture.ticker().advance(java.time.Duration.ofSeconds(35));
      assertThat(cache.containsKey(1)).isFalse();
    }
  }

  @ParameterizedTest @MethodSource("throwables")
  void reload_failure(Throwable throwable) {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.load(1)).thenReturn(-1).thenThrow(throwable);

    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setReadThrough(true);
          config.setCacheLoaderFactory(() -> loader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setExecutorFactory(MoreExecutors::directExecutor);
          config.setRefreshAfterWrite(OptionalLong.of(TimeUnit.MINUTES.toNanos(1)));
        }).build();
        var cache = fixture.jcacheLoading()) {
      assertThat(cache.get(1)).isEqualTo(-1);
      fixture.ticker().advance(java.time.Duration.ofMinutes(2));

      // The reload throws; the refresh fails and the entry keeps its prior value.
      assertThat(cache.get(1)).isEqualTo(-1);
      verify(loader, Mockito.atLeast(2)).load(1);
    }
  }

  @Test
  void loadAll_keepExisting_reloadsJCacheExpired() {
    // A native expireAfterWrite (long) alongside a shorter jcache ExpiryPolicy: native expiry no
    // longer mirrors the Expirable, so a jcache-expired entry stays physically present and
    // keep-existing must still reload it
    ExpiryPolicy expiry = Mockito.mock(answer -> new Duration(TimeUnit.MINUTES, 1));
    CacheLoader<Integer, Integer> loader = Mockito.mock();
    when(loader.loadAll(any())).thenAnswer(invocation -> {
      var loaded = new HashMap<Integer, Integer>();
      invocation.<Iterable<Integer>>getArgument(0).forEach(key -> loaded.put(key, -2));
      return loaded;
    });

    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setReadThrough(true);
          config.setCacheLoaderFactory(() -> loader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setExpireAfterWrite(OptionalLong.of(TimeUnit.HOURS.toNanos(1)));
          config.setExecutorFactory(MoreExecutors::directExecutor);
        }).build();
        var cache = fixture.jcacheLoading()) {
      cache.put(1, -1);
      fixture.ticker().advance(java.time.Duration.ofMinutes(2));

      cache.loadAll(Set.of(1), /* replaceExistingValues= */ false, null);

      assertThat(cache.get(1)).isEqualTo(-2);
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
    // Per JSR-107 1.1.1 p.55: "Should an exception occur while determining the
    // Duration, an implementation specific default Duration will be used."
    // The load returns the value; the exception is swallowed and logged.
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
      when(cacheLoader.load(any())).thenReturn(-1);
      assertThat(fixture.jcacheLoading().get(1)).isEqualTo(-1);
    }
  }

  @Test
  void load_adjustedTimeSentinelZero() {
    // A computed creation expiry that collides with the ZERO ("already expired") sentinel is
    // clamped (like the put path, CacheProxy.getWriteExpireTimeMillis) so the loaded entry is
    // stored rather than mistaken for the not-added sentinel and dropped. Per JSR-107 the caller
    // that triggers the load still receives the value, whereas the not-added sentinel returns null.
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
    var duration = Mockito.spy(new Duration(TimeUnit.MILLISECONDS, 1));
    when(duration.getAdjustedTime(anyLong())).thenReturn(0L);
    when(expiry.getExpiryForCreation()).thenReturn(duration);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.load(any())).thenReturn(-1);
      assertThat(fixture.jcacheLoading().get(1)).isEqualTo(-1);
    }
  }

  @Test
  void load_adjustedTimeSentinelMax() {
    // A computed creation expiry that collides with the ETERNAL sentinel (Long.MAX_VALUE) is
    // clamped to a finite expiry so the entry is not mistaken for one that never expires.
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
    var duration = Mockito.spy(new Duration(TimeUnit.MILLISECONDS, 1));
    when(duration.getAdjustedTime(anyLong())).thenReturn(Long.MAX_VALUE);
    when(expiry.getExpiryForCreation()).thenReturn(duration);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.load(any())).thenReturn(-1);
      assertThat(fixture.jcacheLoading().get(1)).isEqualTo(-1);
    }
  }

  @Test
  void load_nullCreationExpiry_isEternal() {
    // getExpiryForCreation() returning null (unlike ZERO or a finite Duration) leaves the loaded
    // entry eternal rather than dropping or expiring it, matching the put path's default.
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
    when(expiry.getExpiryForCreation()).thenReturn(null);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(cacheLoader.load(any())).thenReturn(-1);
      assertThat(fixture.jcacheLoading().get(1)).isEqualTo(-1);

      fixture.ticker().advance(java.time.Duration.ofDays(1));
      assertThat(fixture.jcacheLoading().containsKey(1)).isTrue();
    }
  }

  @Test
  void load_zeroExpiry_publishesExpiredNotCreated() {
    // Per JSR-107 1.1.1 p.55: "If a Duration#ZERO is returned the new Cache.Entry
    // is considered to be already expired and will not be added to the Cache."
    // Match the put-path convention: publish EXPIRED in place of CREATED.
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    CacheEntryCreatedListener<Integer, Integer> created = Mockito.mock();
    CacheEntryExpiredListener<Integer, Integer> expired = Mockito.mock();
    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setCacheLoaderFactory(() -> cacheLoader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setReadThrough(true);
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> created, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> expired, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        }).build()) {
      when(cacheLoader.load(any())).thenReturn(-1);
      assertThat(fixture.jcacheLoading().get(1)).isNull();
      assertThat(fixture.jcacheLoading().containsKey(1)).isFalse();
      verify(created, never()).onCreated(any());
      verify(expired).onExpired(any());
    }
  }

  @Test
  void loadAll_zeroExpiry_publishesExpiredNotCreated() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    CacheEntryCreatedListener<Integer, Integer> created = Mockito.mock();
    CacheEntryExpiredListener<Integer, Integer> expired = Mockito.mock();
    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setCacheLoaderFactory(() -> cacheLoader);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setReadThrough(true);
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> created, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> expired, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        }).build()) {
      when(cacheLoader.loadAll(anyIterable())).thenReturn(Map.of(1, -1, 2, -2));
      assertThat(fixture.jcacheLoading().getAll(Set.of(1, 2))).isEmpty();
      verify(created, never()).onCreated(any());
      verify(expired, Mockito.atLeastOnce()).onExpired(any());
    }
  }

  @Test
  void load_failure_expiry_publishesCreated() {
    // Counterpart to load_failure_expiry: with the policy throwing, the load
    // still succeeds (default Duration is used) and CREATED is published.
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    CacheEntryCreatedListener<Integer, Integer> listener = Mockito.mock();
    try (var fixture = JCacheFixture.builder()
        .loading(config -> {
          config.setReadThrough(true);
          config.setExpiryPolicyFactory(() -> expiry);
          config.setCacheLoaderFactory(() -> cacheLoader);
          config.addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
              /* listenerFactory= */ () -> listener, /* filterFactory= */ null,
              /* isOldValueRequired= */ false, /* isSynchronous= */ true));
        }).build()) {
      when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
      when(cacheLoader.load(any())).thenReturn(-1);
      assertThat(fixture.jcacheLoading().get(1)).isEqualTo(-1);
      verify(listener).onCreated(any());
    }
  }

  @Test
  void loadAll() {
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ETERNAL);
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
    // Per JSR-107 1.1.1 p.55: a thrown getExpiryForCreation uses an
    // implementation default. The bulk load still succeeds.
    ExpiryPolicy expiry = Mockito.mock(answer -> Duration.ZERO);
    CacheLoader<Integer, Integer> cacheLoader = Mockito.mock();
    try (var fixture = jcacheFixture(expiry, cacheLoader)) {
      when(expiry.getExpiryForCreation()).thenThrow(IllegalStateException.class);
      when(cacheLoader.loadAll(anyIterable())).thenReturn(Map.of(1, 1, 2, 2));
      var result = fixture.jcacheLoading().getAll(Set.of(1, 2, 3));
      assertThat(result).containsExactly(1, 1, 2, 2);
    }
  }

  @MethodSource
  static Stream<Exception> throwables() {
    return Stream.of(new IllegalStateException(), new CacheLoaderException());
  }
}
