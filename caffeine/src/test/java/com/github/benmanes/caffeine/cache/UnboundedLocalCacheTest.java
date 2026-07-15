/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.slf4j.event.Level.TRACE;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The test cases for the implementation details of {@link UnboundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(TRACE)
final class UnboundedLocalCacheTest {

  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.DISABLED,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  @ParameterizedTest
  void noPolicy(Cache<Integer, Integer> cache) {
    assertThat(cache.policy().eviction()).isEmpty();
    assertThat(cache.policy().expireAfterWrite()).isEmpty();
    assertThat(cache.policy().expireAfterAccess()).isEmpty();
    assertThat(cache.policy().refreshAfterWrite()).isEmpty();
  }

  @Test
  void refreshes_memoize() {
    // The refresh map is never unset once initialized and a CAS race can cause a thread's attempt
    // at initialization to fail so it re-reads for the current value. This asserts a non-null value
    // for NullAway's static analysis. We can test the failed CAS scenario by resetting the field
    // and catching the NullPointerException, thereby proving that the failed initialization falls
    // back to a re-read. This error will not happen in practice since the field is not modified
    // again.
    var signal = new CompletableFuture<@Nullable Void>().orTimeout(10, TimeUnit.SECONDS);
    var cache = new UnboundedLocalCache<>(Caffeine.newBuilder(), /* isAsync= */ false);
    ConcurrentTestHarness.timeTasks(10, new Runnable() {
      @SuppressFBWarnings("DCN_NULLPOINTER_EXCEPTION")
      @Override public void run() {
        while (!signal.isDone()) {
          try {
            cache.refreshes = null;
            assertThat(cache.refreshes()).isNotNull();
          } catch (NullPointerException e) {
            signal.complete(null);
            return;
          }
        }
      }
    });
    assertThat(signal.join()).isNull();
  }

  @Test
  void computeIfAbsent_discardsRefresh() {
    var cache = new UnboundedLocalCache<Integer, Integer>(
        Caffeine.newBuilder(), /* isAsync= */ false);
    Integer key = 1;
    cache.refreshes().put(key, new CompletableFuture<>());

    var result = cache.computeIfAbsent(key, k -> 42,
        /* recordStats= */ false, /* recordLoad= */ false);
    assertThat(result).isEqualTo(42);
    assertThat(cache.refreshes()).doesNotContainKey(key);
  }

  @Test
  void computeIfAbsent_discardsRefresh_null() {
    var cache = new UnboundedLocalCache<Integer, Integer>(
        Caffeine.newBuilder(), /* isAsync= */ false);
    Integer key = 1;
    cache.refreshes().put(key, new CompletableFuture<>());

    Function<Integer, @Nullable Integer> mappingFunction = k -> null;
    var result = cache.computeIfAbsent(key, mappingFunction,
        /* recordStats= */ false, /* recordLoad= */ false);
    assertThat(result).isNull();
    assertThat(cache.refreshes()).doesNotContainKey(key);
  }

  @Test
  void computeIfAbsent_present() {
    var cache = new UnboundedLocalCache<Integer, Integer>(
        Caffeine.newBuilder(), /* isAsync= */ false);
    Integer key = 1;
    assertThat(cache.put(key, 2)).isNull();

    // The stats-free fast-path hit, as when a refresh races a concurrent insert.
    var result = cache.computeIfAbsent(key, k -> { throw new AssertionError(); },
        /* recordStats= */ false, /* recordLoad= */ false);
    assertThat(result).isEqualTo(2);
  }

  @Test
  void remap_preserveRefresh_discardsOnValueChange() {
    var cache = new UnboundedLocalCache<Integer, Integer>(
        Caffeine.newBuilder(), /* isAsync= */ false);
    Integer key = 1;
    cache.refreshes().put(key, new CompletableFuture<>());

    // The hint asks to preserve the refresh, but the remapping is not a no-op (newValue != value),
    // so the refresh is still discarded; a stale reload cannot overwrite the new mapping
    var hints = new LocalCache.RemapHints();
    hints.preserveRefresh = true;
    var result = cache.remap(key, (k, value) -> 2, hints, /* computeIfAbsent= */ true);

    assertThat(result).isEqualTo(2);
    assertThat(cache.refreshes()).doesNotContainKey(key);
  }

  @Test
  void remap_replaceAll_vanishedKey_preservesRefresh() {
    var cache = new UnboundedLocalCache<Integer, Integer>(
        Caffeine.newBuilder(), /* isAsync= */ false);
    Integer key = 1;
    cache.refreshes().put(key, new CompletableFuture<>());

    // A non-creating remap (replaceAll) on a vanished key is a skip, not a mutation, so a refresh
    // registered after the removal must survive, matching the bounded cache.
    var result = cache.remap(key, (k, value) -> (value == null) ? null : value,
        /* hints= */ null, /* computeIfAbsent= */ false);

    assertThat(result).isNull();
    assertThat(cache.refreshes()).containsKey(key);
  }

  @Test
  void remap_discardsRefresh_whenFunctionThrows() {
    var cache = new UnboundedLocalCache<Integer, Integer>(
        Caffeine.newBuilder(), /* isAsync= */ false);
    Integer key = 1;
    assertThat(cache.put(key, 2)).isNull();
    cache.refreshes().put(key, new CompletableFuture<>());

    // A throwing remap over a live entry discards a racing refresh, matching the bounded cache.
    var expected = new IllegalStateException();
    var thrown = assertThrows(IllegalStateException.class, () ->
        cache.remap(key, (k, value) -> { throw expected; },
            /* hints= */ null, /* computeIfAbsent= */ true));

    assertThat(thrown).isSameInstanceAs(expected);
    assertThat(cache.refreshes()).doesNotContainKey(key);
    assertThat(cache.getIfPresent(key, /* recordStats= */ false)).isEqualTo(2);
  }

  @Test
  void computeIfPresent_discardsRefresh_whenFunctionThrows() {
    var cache = new UnboundedLocalCache<Integer, Integer>(
        Caffeine.newBuilder(), /* isAsync= */ false);
    Integer key = 1;
    assertThat(cache.put(key, 2)).isNull();
    cache.refreshes().put(key, new CompletableFuture<>());

    var expected = new IllegalStateException();
    var thrown = assertThrows(IllegalStateException.class, () ->
        cache.computeIfPresent(key, (k, value) -> { throw expected; }));

    assertThat(thrown).isSameInstanceAs(expected);
    assertThat(cache.refreshes()).doesNotContainKey(key);
    assertThat(cache.getIfPresent(key, /* recordStats= */ false)).isEqualTo(2);
  }
}
