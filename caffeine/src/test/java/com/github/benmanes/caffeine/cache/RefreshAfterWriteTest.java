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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.executor;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Nullness.nullFuture;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.Policy.FixedRefresh;
import com.github.benmanes.caffeine.testing.ExpectedError;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

/**
 * The test cases for caches that support the refresh after write policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(TRACE)
final class RefreshAfterWriteTest {

  /* --------------- refreshIfNeeded --------------- */

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void refreshIfNeeded_nonblocking(CacheContext context) {
    Int key = context.absentKey();
    Int original = intern(Int.valueOf(1));
    Int refresh1 = intern(original.add(1));
    Int refresh2 = intern(refresh1.add(1));
    var duration = Duration.ofMinutes(2);

    var refresh = new AtomicBoolean();
    var reloads = new AtomicInteger();
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override
      public CompletableFuture<Int> asyncReload(Int key, Int oldValue, Executor executor) {
        reloads.incrementAndGet();
        await().untilTrue(refresh);
        return oldValue.add(1).toFuture();
      }
    });
    cache.put(key, original);
    context.ticker().advance(duration);
    var future = CompletableFuture.supplyAsync(() -> cache.get(key), executor);
    await().untilAsserted(() -> assertThat(reloads.get()).isEqualTo(1));

    assertThat(cache.get(key)).isEqualTo(original);
    refresh.set(true);

    Int refreshed = cache.get(key);
    assertThat(refreshed).isAnyOf(original, refresh1);
    assertThat(future.join()).isAnyOf(original, refresh1);

    await().untilAsserted(() -> assertThat(reloads.get()).isEqualTo(1));
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, refresh1));
    await().untilAsserted(() -> assertThat(cache.policy().refreshes()).isEmpty());

    context.ticker().advance(duration);
    assertThat(cache.get(key)).isEqualTo(refresh2);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void refreshIfNeeded_sameInstance_doesNotSuppressLaterRefresh(CacheContext context) {
    // a same-instance reload must still clear the refresh token, else a leaked token would gate
    // refreshIfNeeded and suppress every later refresh for the key
    Int key = context.absentKey();
    Int value = intern(Int.valueOf(1));
    var reloads = new AtomicInteger();
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        reloads.incrementAndGet();
        return oldValue.toFuture();
      }
    });
    cache.put(key, value);

    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(value);
    await().untilAsserted(() -> assertThat(reloads.get()).isEqualTo(1));
    await().untilAsserted(() -> assertThat(cache.policy().refreshes()).isEmpty());

    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(value);
    await().untilAsserted(() -> assertThat(reloads.get()).isEqualTo(2));
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void refreshIfNeeded_failure(CacheContext context) {
    Int key = context.absentKey();
    var reloads = new AtomicInteger();
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override
      public CompletableFuture<Int> asyncReload(Int key, Int oldValue, Executor executor) {
        reloads.incrementAndGet();
        throw new IllegalStateException();
      }
    });
    cache.put(key, key);

    for (int i = 0; i < 5; i++) {
      context.ticker().advance(Duration.ofMinutes(2));
      Int value = cache.get(key);
      assertThat(value).isEqualTo(key);

      int count = i + 1;
      await().untilAsserted(() -> assertThat(reloads.get()).isEqualTo(count));
    }
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL,
      loader = Loader.REFRESH_INTERRUPTED, refreshAfterWrite = Expire.ONE_MINUTE)
  void refreshIfNeeded_interrupted(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());

    assertThat(value).isNotNull();
    assertThat(Thread.interrupted()).isTrue();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING)
  void refreshIfNeeded_replace(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentKey());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentKey())
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, refreshAfterWrite = Expire.ONE_MINUTE,
      removalListener = Listener.CONSUMING, loader = Loader.NULL)
  void refreshIfNeeded_remove(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(cache).doesNotContainKey(context.absentKey());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.absentKey(), context.absentValue())
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.EMPTY,
      removalListener = Listener.CONSUMING)
  void refreshIfNeeded_noChange(CacheContext context) {
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @CanIgnoreReturnValue
      @Override public Int reload(Int key, Int oldValue) {
        return oldValue;
      }
    });
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_replaced(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    cache.put(context.firstKey(), context.absentValue());
    future.complete(context.absentKey().negate());

    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(
            Map.entry(context.firstKey(), context.original().get(context.firstKey())),
            Map.entry(context.firstKey(), context.absentKey().negate()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_replaced_compute(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    cache.asMap().compute(context.firstKey(), (k, v) -> context.absentValue());
    future.complete(context.absentKey().negate());

    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    assertThat(cache.policy().refreshes()).doesNotContainKey(context.firstKey());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(
            Map.entry(context.firstKey(), context.original().get(context.firstKey())),
            Map.entry(context.firstKey(), context.absentKey().negate()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_replacedNull(
      LoadingCache<Int, @Nullable Int> cache, CacheContext context) {
    // A refresh that resolves to null and is preempted by a concurrent put must not fire a
    // REPLACED notification with a null value — the RemovalListener contract promises a
    // non-null value for REPLACED.
    var originalValue = requireNonNull(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    CompletableFuture<@Nullable Int> future = requireNonNull(
        cache.policy().refreshes().get(context.firstKey()));

    cache.put(context.firstKey(), context.absentValue());
    future.complete(null);

    assertThat(cache).containsEntry(context.firstKey(), context.absentValue());
    // Only the put-over-original REPLACED should be observed; the null refresh must be silently
    // discarded.
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), originalValue)
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, expireAfterWrite = Expire.FOREVER,
      removalListener = Listener.CONSUMING, loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_rejected_preservesWriteTime(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // A refresh that is rejected by a concurrent write must not bump writeTime/accessTime when
    // its compute falls through — otherwise the entry's expiration shifts by the refresh
    // duration even though the refresh produced no state change.
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    // Put discards the refresh; writeTime is now "just written"
    cache.put(context.firstKey(), context.absentValue());
    var expireAfterWrite = cache.policy().expireAfterWrite().orElseThrow();
    var ageAfterPut = expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS);
    assertThat(ageAfterPut).hasValue(0L);

    // Let time elapse, then complete the rejected refresh; the writeTime must NOT shift forward
    context.ticker().advance(Duration.ofSeconds(30));
    future.complete(context.absentKey().negate());

    var ageAfterRefresh = expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS);
    assertThat(ageAfterRefresh).hasValue(Duration.ofSeconds(30).toNanos());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, expireAfterWrite = Expire.FOREVER,
      removalListener = Listener.CONSUMING, loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_rejectedSameInstance_preservesWriteTime(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // ABA case: a concurrent put re-inserts the same value instance, bumping writeTime without
    // changing the value reference. The refresh rejection must preserve the put's writeTime
    // rather than shift it forward to the refresh completion time.
    var originalValue = requireNonNull(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    cache.put(context.firstKey(), originalValue);
    var expireAfterWrite = cache.policy().expireAfterWrite().orElseThrow();
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS)).hasValue(0L);

    context.ticker().advance(Duration.ofSeconds(30));
    future.complete(context.absentKey().negate());

    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS))
        .hasValue(Duration.ofSeconds(30).toNanos());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, expireAfterWrite = Expire.FOREVER,
      removalListener = Listener.CONSUMING, loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_rejectedSameReload_preservesWriteTime(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // A concurrent put bumps the writeTime and the reload then completes with that same value
    // instance. The rejected refresh must preserve the put's writeTime rather than treat the
    // equal-value completion as a fresh write and shift the entry's expiration forward.
    var originalValue = requireNonNull(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));
    cache.put(context.firstKey(), originalValue);
    var expireAfterWrite = cache.policy().expireAfterWrite().orElseThrow();
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS)).hasValue(0L);

    context.ticker().advance(Duration.ofSeconds(30));
    future.complete(originalValue);

    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS))
        .hasValue(Duration.ofSeconds(30).toNanos());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, expireAfterWrite = Expire.FOREVER,
      removalListener = Listener.CONSUMING, loader = Loader.ASYNC_INCOMPLETE)
  void refresh_rejected_preservesWriteTime(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // Same invariant as above, but triggered by the explicit refresh(key) API — exercises the
    // compute paths in LocalLoadingCache.refresh (sync) and LocalAsyncLoadingCache
    // .tryComputeRefresh (async) depending on the parameterized compute mode.
    var refreshFuture = cache.refresh(context.firstKey());
    assertThat(refreshFuture).isNotDone();

    cache.put(context.firstKey(), context.absentValue());
    var expireAfterWrite = cache.policy().expireAfterWrite().orElseThrow();
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS)).hasValue(0L);

    context.ticker().advance(Duration.ofSeconds(30));
    refreshFuture.complete(context.absentKey().negate());

    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS))
        .hasValue(Duration.ofSeconds(30).toNanos());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, expireAfterWrite = Expire.FOREVER,
      removalListener = Listener.CONSUMING, loader = Loader.ASYNC_INCOMPLETE)
  void refresh_sameInstance_updatesWriteTime(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // A successful refresh that reloads the same value instance still resets the write time — a
    // same-instance setter no-op, not a metadata no-op — so the entry is not treated as unwritten.
    var originalValue = requireNonNull(context.original().get(context.firstKey()));
    var refreshFuture = cache.refresh(context.firstKey());
    assertThat(refreshFuture).isNotDone();
    var expireAfterWrite = cache.policy().expireAfterWrite().orElseThrow();

    context.ticker().advance(Duration.ofSeconds(30));
    refreshFuture.complete(originalValue);

    assertThat(refreshFuture).succeedsWith(originalValue);
    assertThat(cache).containsEntry(context.firstKey(), originalValue);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.NANOSECONDS)).hasValue(0L);
    assertThat(context).removalNotifications().isEmpty();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  void refresh_staleCompletion_doesNotDiscardSuccessor(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // A stale refresh completing after a newer refresh has registered must not discard the
    // successor's registration by key; the refreshes map holds one future per key, so a by-key
    // discard is only safe for the owner. Otherwise the successor's fresh reload is thrown away
    // and the cache is left stale. Exercises the reject branch of LocalLoadingCache.refresh (sync)
    // and LocalAsyncLoadingCache.tryComputeRefresh (async) per the parameterized compute mode.
    Int key = context.firstKey();

    var r1 = cache.refresh(key);
    assertThat(r1).isNotDone();

    // A write discards R1's registration; R2 then registers for the new value
    cache.put(key, context.absentValue());
    var r2 = cache.refresh(key);
    assertThat(r2).isNotDone();

    // Stale R1 completes first: it must leave R2's registration intact
    r1.complete(context.absentKey().negate());
    assertThat(cache.policy().refreshes()).containsKey(key);

    // R2's successful reload must be committed, not silently discarded
    r2.complete(context.absentKey());
    assertThat(cache).containsEntry(key, context.absentKey());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_staleCompletion_doesNotDiscardSuccessor(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // Same stale-first steal as refresh_staleCompletion_doesNotDiscardSuccessor, but via the
    // automatic refreshAfterWrite path (BoundedLocalCache.refreshIfNeeded) triggered on the read
    // after the write threshold elapses.
    Int key = context.absentKey();
    Int original = context.absentValue();
    Int updated = original.add(1);
    Int reloaded = original.add(2);
    cache.put(key, original);

    // R1 auto-refresh registers for the original snapshot
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(original);
    var r1 = requireNonNull(cache.policy().refreshes().get(key));

    // A write discards R1's registration and resets the refresh clock
    cache.put(key, updated);
    assertThat(cache.policy().refreshes()).doesNotContainKey(key);

    // R2 auto-refresh registers for the updated snapshot
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(updated);
    var r2 = requireNonNull(cache.policy().refreshes().get(key));

    // Stale R1 completes first: it must leave R2's registration intact
    r1.complete(reloaded.negate());
    assertThat(cache.policy().refreshes()).containsKey(key);

    // R2's fresh reload must be committed
    r2.complete(reloaded);
    assertThat(cache).containsEntry(key, reloaded);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE)
  void refresh_staleAbsentCompletion_doesNotDiscardSuccessor(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // A stale refresh that completes while the entry is absent returns null, and remap's
    // absent-exit must not discard a successor's registration by key. The successor here refreshes
    // the absent key (asyncLoad); its loaded value must survive rather than being dropped.
    Int key = context.absentKey();
    cache.put(key, context.absentValue());

    var r1 = cache.refresh(key);
    assertThat(r1).isNotDone();

    // Invalidate: entry absent, R1's registration discarded
    cache.invalidate(key);
    assertThat(cache.policy().refreshes()).doesNotContainKey(key);

    // R2 refreshes the now-absent key (asyncLoad) and registers as a successor
    var r2 = cache.refresh(key);
    assertThat(r2).isNotDone();

    // Stale R1 completes while absent: it must leave R2's registration intact
    r1.complete(context.absentValue());
    assertThat(cache.policy().refreshes()).containsKey(key);

    // R2's successful load must populate the entry, not be silently discarded
    r2.complete(context.absentKey());
    assertThat(cache).containsEntry(key, context.absentKey());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_staleAbsentCompletion_doesNotDiscardSuccessor(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // Same absent-exit steal, but the stale refresh is the automatic refreshAfterWrite reload
    // (BoundedLocalCache.refreshIfNeeded), whose absent branch must also honor the hint.
    Int key = context.absentKey();
    cache.put(key, context.absentValue());

    // R1 auto-refresh registers for the present snapshot
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(context.absentValue());
    var r1 = requireNonNull(cache.policy().refreshes().get(key));

    // Invalidate: entry absent, R1's registration discarded
    cache.invalidate(key);
    assertThat(cache.policy().refreshes()).doesNotContainKey(key);

    // R2 explicit refresh of the absent key (asyncLoad) registers as a successor
    var r2 = cache.refresh(key);
    assertThat(r2).isNotDone();

    // Stale auto-refresh R1 completes while absent: it must leave R2's registration intact
    r1.complete(context.absentValue());
    assertThat(cache.policy().refreshes()).containsKey(key);

    // R2's successful load must populate the entry
    r2.complete(context.absentKey());
    assertThat(cache).containsEntry(key, context.absentKey());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_writeTimeABA(LoadingCache<Int, Int> cache, CacheContext context) {
    // When a refresh is in-flight and a put replaces the value with the SAME instance,
    // currentValue == oldValue is true but the write time changed. The completion's ABA check
    // compares the base write time (masking off the soft-lock marker bit) and so still detects
    // this and discards the refresh.
    var originalValue = requireNonNull(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    // Re-put the same value instance — changes writeTime but not the value reference.
    // notifyOnReplace is a no-op for same-instance replacement.
    cache.put(context.firstKey(), originalValue);
    future.complete(context.absentKey().negate());

    // The put's value should be preserved; the refresh should be discarded
    assertThat(cache).containsEntry(context.firstKey(), originalValue);
    // Only the discarded refresh produces a REPLACED notification
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.absentKey().negate())
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_completed_appliesAndClearsToken(
      LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    // No mutation races the reload: the completion must apply it (the marker-aware write-time check
    // must not mistake a transient soft-lock for a write) and clear the token once the swap is done
    future.complete(context.absentKey().negate());

    assertThat(cache).containsEntry(context.firstKey(), context.absentKey().negate());
    assertThat(cache.policy().refreshes()).doesNotContainKey(context.firstKey());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MILLISECOND, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE, mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expiry = {CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS},
      expiryTime = Expire.ONE_MINUTE, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  void refreshIfNeeded_expired(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(10));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    context.ticker().advance(Duration.ofMinutes(1));
    future.complete(context.absentValue());
    assertThat(cache).isEmpty();

    assertThat(context).removalNotifications().withCause(EXPIRED)
        .contains(context.original());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(Map.entry(context.firstKey(), context.absentValue()));
    assertThat(context).removalNotifications().hasSize(context.original().size() + 1);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_absent_newValue(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    cache.invalidate(context.firstKey());
    assertThat(cache).doesNotContainKey(context.firstKey());

    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(context.firstKey());

    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(
            Map.entry(context.firstKey(), context.original().get(context.firstKey())),
            Map.entry(context.firstKey(), context.absentValue()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE, executor = CacheExecutor.THREADED)
  void refreshIfNeeded_absent_nullValue(
      LoadingCache<Int, @Nullable Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    assertThat(cache.policy().refreshes()).isNotEmpty();
    CompletableFuture<@Nullable Int> future =
        requireNonNull(cache.policy().refreshes().get(context.firstKey()));

    cache.invalidate(context.firstKey());
    future.complete(null);

    assertThat(cache).doesNotContainKey(context.firstKey());
    assertThat(context).removalNotifications().withCause(EXPLICIT)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      keys = ReferenceType.WEAK, refreshAfterWrite = Expire.ONE_MINUTE,
      removalListener = Listener.CONSUMING, loader = Loader.ASYNC_INCOMPLETE)
  void refreshIfNeeded_weakKeyRemoved(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, context.absentValue());

    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isSameInstanceAs(context.absentValue());

    await().untilAsserted(() -> assertThat(cache.policy().refreshes()).containsKey(key));
    var future = requireNonNull(cache.policy().refreshes().get(key));

    cache.invalidate(key);
    assertThat(cache.policy().refreshes()).doesNotContainKey(key);

    future.complete(context.absentValue());
    assertThat(cache).doesNotContainKey(key);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE)
  void refresh_absentThenInvalidate_doesNotResurrect(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // A refresh of an absent key registers a reload with no data-map node; invalidate(k) must
    // still discard it so the completing reload cannot resurrect the key past the purge
    Int key = context.absentKey();
    var refresh = cache.refresh(key);
    assertThat(cache.policy().refreshes()).containsKey(key);

    cache.invalidate(key);
    refresh.complete(context.absentValue());

    assertThat(cache).doesNotContainKey(key);
    assertThat(cache.policy().refreshes()).doesNotContainKey(key);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE)
  void refresh_absentThenInvalidateAll_doesNotResurrect(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // Same via a full invalidateAll(): clear()'s node loop can't reach a node-less absent-key
    // registration, so it must purge pending refreshes
    Int key = context.absentKey();
    var refresh = cache.refresh(key);
    assertThat(cache.policy().refreshes()).containsKey(key);

    cache.invalidateAll();
    refresh.complete(context.absentValue());

    assertThat(cache).doesNotContainKey(key);
    assertThat(cache.policy().refreshes()).doesNotContainKey(key);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      compute = Compute.SYNC, loader = Loader.ASYNC_INCOMPLETE,
      maximumSize = { Maximum.DISABLED, Maximum.FULL })
  void compute_absentThrows_keepsRefresh(LoadingCache<Int, Int> cache, CacheContext context) {
    // A compute whose user function throws on an absent key created nothing, so an independent
    // in-flight refresh must survive — on both the bounded and unbounded siblings (the unbounded
    // remap's blanket catch used to discard on this absent path)
    Int key = context.absentKey();
    var refresh = cache.refresh(key);
    assertThat(cache.policy().refreshes()).containsKey(key);

    assertThrows(IllegalStateException.class,
        () -> cache.asMap().compute(key, (k, v) -> { throw new IllegalStateException(); }));

    // The aborted compute created nothing, so the independent refresh is left intact
    assertThat(cache.policy().refreshes()).containsKey(key);

    // Let it complete so it populates the key (and clears its token for the teardown check)
    refresh.complete(context.absentValue());
    assertThat(cache).containsEntry(key, context.absentValue());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, executor = CacheExecutor.THREADED)
  void refresh_absent_sideLoad_dupLoads(CacheContext context) throws InterruptedException {
    // A refresh of an absent key is an isolated side-load on the sync cache (registered only in
    // refreshes(), invisible to a concurrent get, which loads again) but a first-class in-flight
    // entry on the async view (a concurrent get joins it). Pins the intentional A2-F1a divergence.
    var loads = new AtomicInteger();
    var release = new CountDownLatch(1);
    var loadStarted = new CountDownLatch(1);
    CacheLoader<Int, Int> loader = key -> {
      loads.incrementAndGet();
      loadStarted.countDown();
      release.await();
      return key.negate();
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(loader).synchronous()
        : context.build(loader);
    Int key = context.absentKey();

    // Kick off the reload asynchronously (default asyncLoad = supplyAsync), then wait until it is
    // registered and running so the concurrent get below races an in-flight reload
    var refresh = cache.refresh(key);
    loadStarted.await();

    var get = CompletableFuture.supplyAsync(() -> cache.get(key), executor);
    if (!context.isAsync()) {
      // The sync get cannot see the invisible side-load, so it starts a second load
      await().until(() -> loads.get() == 2);
    }
    release.countDown();

    assertThat(get.join()).isEqualTo(key.negate());
    refresh.join();
    await().until(() -> cache.policy().refreshes().isEmpty());

    assertThat(loads.get()).isEqualTo(context.isAsync() ? 1 : 2);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void refreshIfNeeded_cancel_noLog(CacheContext context) {
    var cacheLoader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        var future = new CompletableFuture<Int>();
        future.cancel(false);
        return future;
      }
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(logEvents()).isEmpty();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void refreshIfNeeded_timeout_noLog(CacheContext context) {
    var cacheLoader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        var future = new CompletableFuture<Int>();
        future.orTimeout(0, TimeUnit.SECONDS);
        await().until(future::isDone);
        return future;
      }
    };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(logEvents()).isEmpty();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO,
      expiryTime = Expire.FOREVER, refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY)
  void refreshIfNeeded_expiryThrows_logsAndRecordsFailure(
      LoadingCache<Int, Int> cache, CacheContext context) {
    // If the user's Expiry throws during the refresh handler's compute (after the loader
    // succeeded), the exception must be logged and the load recorded as a failure; otherwise
    // the stats/log contract documented on LoadingCache.refresh is silently violated.
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpectedError.INSTANCE);

    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.getIfPresent(context.absentKey())).isNotNull();

    // Use getIfPresentQuietly to avoid triggering a second refresh attempt.
    assertThat(cache.policy().getIfPresentQuietly(context.absentKey()))
        .isSameInstanceAs(context.absentValue());
    assertThat(context).stats().failures(1).success(0);
    assertThat(logEvents()
        .withMessage("Exception thrown during refresh")
        .withThrowable(ExpectedError.INSTANCE)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.MOCKITO)
  void refreshIfNeeded_weigherThrows_clearsToken(
      LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.weigher().weigh(any(), any())).thenReturn(1);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    when(context.weigher().weigh(any(), any())).thenThrow(ExpectedError.INSTANCE);

    // The reload's weigh throws inside the refresh completion; the failure is swallowed, but the
    // entry's refresh token must still be cleared rather than orphaned in refreshes() (#1970).
    assertThat(cache.getIfPresent(context.absentKey())).isNotNull();
    assertThat(cache.policy().refreshes()).isEmpty();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.EMPTY, expiry = CacheExpiry.MOCKITO,
      expiryTime = Expire.FOREVER, refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY)
  void refresh_expiryThrows_logsAndRecordsFailure(
      LoadingCache<Int, Int> cache, CacheContext context) {
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenThrow(ExpectedError.INSTANCE);

    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    cache.refresh(context.absentKey());

    assertThat(cache.policy().getIfPresentQuietly(context.absentKey()))
        .isSameInstanceAs(context.absentValue());
    assertThat(context).stats().failures(1).success(0);
    assertThat(logEvents()
        .withMessage("Exception thrown during refresh")
        .withThrowable(ExpectedError.INSTANCE)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void refreshIfNeeded_error_log(CacheContext context) {
    var expected = new RuntimeException();
    CacheLoader<Int, Int> cacheLoader = key -> { throw expected; };
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(cacheLoader).synchronous()
        : context.build(cacheLoader);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isEqualTo(context.absentValue());
    assertThat(logEvents()
        .withMessage("Exception thrown during refresh")
        .withUnderlyingCause(expected)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void refreshIfNeeded_nullFuture(CacheContext context) {
    var refreshed = new AtomicBoolean();
    var loader = new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        refreshed.set(true);
        return nullFuture();
      }
    };

    var cache = context.isAsync()
        ? context.buildAsync(loader).synchronous()
        : context.build(loader);
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.get(context.absentKey());

    assertThat(value).isNotNull();
    assertThat(logEvents()
        .withMessage("Exception thrown when submitting refresh task")
        .withThrowable(NullPointerException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);

    assertThat(refreshed.get()).isTrue();
    assertThat(cache.policy().refreshes()).isEmpty();
    assertThat(cache).containsEntry(context.absentKey(), context.absentValue());
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(loader = Loader.ASYNC_INCOMPLETE,
      refreshAfterWrite = Expire.ONE_MINUTE, expireAfterWrite = Expire.FOREVER)
  void refreshIfNeeded_slowLoad(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    cache.synchronous().asMap().put(context.absentKey(), context.absentKey());

    context.ticker().advance(Duration.ofHours(1));
    cache.put(context.absentKey(), new CompletableFuture<>());

    context.ticker().advance(Duration.ofHours(1));
    var future = requireNonNull(cache.getIfPresent(context.absentKey()));
    assertThat(future).isNotDone();

    assertThat(cache.synchronous().policy().refreshes()).isEmpty();

    future.complete(context.absentKey().negate());
    assertThat(cache.synchronous().policy().refreshes()).isEmpty();

    var expectedMap = new HashMap<>(context.original());
    expectedMap.put(context.absentKey(), future.join());
    assertThat(cache).containsExactlyEntriesIn(expectedMap);
  }

  /* --------------- getIfPresent --------------- */

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void getIfPresent_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey().negate());
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.middleKey(), context.original().get(context.middleKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void getIfPresent_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey().negate());
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getIfPresent(context.middleKey())).isEqualTo(context.middleKey().negate());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().isEmpty();

    if (context.isCaffeine()) {
      var future = requireNonNull(cache.policy().refreshes().get(context.middleKey()));
      future.complete(context.middleKey());
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(context.middleKey(), context.original().get(context.middleKey()))
          .exclusively();
    }
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NEGATIVE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void getIfPresent_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getIfPresent(context.middleKey())).succeedsWith(context.middleKey().negate());
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getIfPresent(context.middleKey())).succeedsWith(context.middleKey().negate());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.middleKey(), context.original().get(context.middleKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void entrySetContains_doesNotRefresh(CacheContext context) {
    var reloads = new AtomicInteger();
    var loader = countingReloader(reloads);
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(loader).synchronous()
        : context.build(loader);
    Int key = context.absentKey();
    Int value = context.absentValue();
    cache.put(key, value);
    context.ticker().advance(Duration.ofMinutes(2));

    // A membership test must be quiet: contains() must not launch a refresh.
    assertThat(cache.asMap().entrySet().contains(Map.entry(key, value))).isTrue();
    assertThat(reloads.get()).isEqualTo(0);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void removeConditionally_doesNotRefresh(CacheContext context) {
    var reloads = new AtomicInteger();
    var loader = countingReloader(reloads);
    LoadingCache<Int, Int> cache = context.isAsync()
        ? context.buildAsync(loader).synchronous()
        : context.build(loader);
    Int key = context.absentKey();
    Int value = context.absentValue();
    cache.put(key, value);
    context.ticker().advance(Duration.ofMinutes(2));

    // A non-matching conditional remove must be quiet: it inspects but must not refresh.
    assertThat(cache.asMap().remove(key, value.add(1))).isFalse();
    assertThat(reloads.get()).isEqualTo(0);
    assertThat(cache).containsEntry(key, value);
  }

  private static CacheLoader<Int, Int> countingReloader(AtomicInteger reloads) {
    return new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Int> asyncReload(
          Int key, Int oldValue, Executor executor) {
        reloads.incrementAndGet();
        return oldValue.toFuture();
      }
    };
  }

  /* --------------- getAllPresent --------------- */

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,  loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  void getAllPresent_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var results = cache.getAllPresent(context.firstMiddleLastKeys());

    assertThat(results).isNotEmpty();
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()))
        .containsExactlyEntriesIn(Maps.toMap(context.firstMiddleLastKeys(), key -> key));

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Maps.filterKeys(context.original(), context.firstMiddleLastKeys()::contains))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  void getAllPresent_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var expected = cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()))
        .containsExactlyEntriesIn(expected);

    if (context.isCaffeine()) {
      var replaced = new HashMap<Int, Int>();
      for (var key : context.firstMiddleLastKeys()) {
        var future = requireNonNull(cache.policy().refreshes().get(key));
        future.complete(key);
        replaced.put(key, context.original().get(key));
      }
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(replaced).exclusively();
    }
  }

  /* --------------- getFunc --------------- */

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  void getFunc_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey(), identity());
    assertThat(value).isEqualTo(context.original().get(context.firstKey()));

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.get(context.lastKey(), identity())).isEqualTo(context.lastKey());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.lastKey(), context.original().get(context.lastKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.PARTIAL, Population.FULL })
  void getFunc_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = key -> requireNonNull(context.original().get(key));
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey(), mappingFunction);
    assertThat(value).isNotNull();

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.get(context.lastKey(), mappingFunction)).isEqualTo(context.lastKey().negate());

    if (context.isCaffeine()) {
      var future = requireNonNull(cache.policy().refreshes().get(context.lastKey()));
      future.complete(context.lastKey());
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(context.lastKey(), context.original().get(context.lastKey()))
          .exclusively();
    }
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  void getFunc_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Function<Int, Int> mappingFunction = key -> requireNonNull(context.original().get(key));
    context.ticker().advance(Duration.ofSeconds(30));
    cache.get(context.firstKey(), mappingFunction).join();
    context.ticker().advance(Duration.ofSeconds(45));
    cache.get(context.lastKey(), mappingFunction).join(); // refreshed

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.lastKey(), context.original().get(context.lastKey()))
        .exclusively();
  }

  /* --------------- get --------------- */

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  void get_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.get(context.firstKey())).isEqualTo(context.firstKey());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.PARTIAL, Population.FULL })
  void get_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    var value = cache.get(context.firstKey());
    assertThat(value).isNotNull();

    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.get(context.firstKey())).isEqualTo(context.firstKey().negate());

    if (context.isCaffeine()) {
      var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));
      future.complete(context.firstKey());
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(context.firstKey(), context.original().get(context.firstKey()))
          .exclusively();
    }
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  void get_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofSeconds(30));
    cache.get(context.firstKey()).join();
    cache.get(context.absentKey()).join();
    context.ticker().advance(Duration.ofSeconds(45));

    var value = cache.getIfPresent(context.firstKey());
    assertThat(value).succeedsWith(context.firstKey());
    assertThat(cache).containsEntry(context.firstKey(), context.firstKey());

    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED,
      compute = Compute.ASYNC)
  void get_sameFuture(CacheContext context) {
    var done = new AtomicBoolean();
    var cache = context.buildAsync((Int key) -> {
      await().untilTrue(done);
      return intern(key.negate());
    });

    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    var original = cache.get(key);
    for (int i = 0; i < 10; i++) {
      context.ticker().advance(Duration.ofMinutes(1));
      var next = cache.get(key);
      assertThat(next).isSameInstanceAs(original);
    }
    done.set(true);
    await().untilAsserted(() -> assertThat(cache.synchronous().policy().refreshes()).isEmpty());
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, key.negate()));
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED)
  void get_slowRefresh(CacheContext context) {
    Int key = context.absentKey();
    Int originalValue = context.absentValue();
    Int refreshedValue = intern(originalValue.add(1));
    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var cache = context.build((Int k) -> {
      started.set(true);
      await().untilTrue(done);
      return refreshedValue;
    });

    cache.put(key, originalValue);

    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(originalValue);

    await().untilTrue(started);
    assertThat(cache).containsEntry(key, originalValue);

    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).isEqualTo(originalValue);

    done.set(true);
    await().untilAsserted(() -> assertThat(cache.policy().refreshes()).isEmpty());
    await().untilAsserted(() -> assertThat(cache).containsEntry(key, refreshedValue));
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.NULL)
  void get_null(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(cache.get(key)).succeedsWith(key);
    assertThat(cache.get(key)).succeedsWithNull();
    assertThat(cache).doesNotContainKey(key);
  }

  /* --------------- getAll --------------- */

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  void getAll_immediate(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = List.of(context.firstKey(), context.absentKey());
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getAll(keys)).containsExactly(
        context.firstKey(), context.firstKey().negate(),
        context.absentKey(), context.absentKey());

    // Trigger a refresh, ensure new values are present
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAll(keys)).containsExactly(
        context.firstKey(), context.firstKey(), context.absentKey(), context.absentKey());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.ASYNC_INCOMPLETE,
      population = { Population.PARTIAL, Population.FULL })
  void getAll_delayed(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    var expected = Maps.toMap(context.firstMiddleLastKeys(), Int::negate);
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getAll(keys)).containsExactlyEntriesIn(expected);

    // Trigger a refresh, returns old values
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(cache.getAll(keys)).containsExactlyEntriesIn(expected);

    if (context.isCaffeine()) {
      for (var key : keys) {
        var future = requireNonNull(cache.policy().refreshes().get(key));
        future.complete(key);
      }
      assertThat(context).removalNotifications().withCause(REPLACED)
          .contains(Maps.filterKeys(context.original(), context.firstMiddleLastKeys()::contains))
          .exclusively();
    }
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  void getAll_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = List.of(context.firstKey(), context.absentKey());
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(cache.getAll(keys).join()).containsExactly(
        context.firstKey(), context.firstKey().negate(),
        context.absentKey(), context.absentKey());

    // Trigger a refresh, may return old values
    context.ticker().advance(Duration.ofSeconds(45));
    cache.getAll(keys).join();

    // Ensure new values are present
    assertThat(cache.getAll(keys).join()).containsExactly(
        context.firstKey(), context.firstKey(), context.absentKey(), context.absentKey());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.firstKey(), context.original().get(context.firstKey()))
        .exclusively();
  }

  /* --------------- put --------------- */

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  void put(CacheContext context) {
    var started = new AtomicBoolean();
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int updated = Int.valueOf(2);
    Int refreshed = Int.valueOf(3);
    var cache = context.build((Int k) -> {
      started.set(true);
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    context.ticker().advance(Duration.ofMinutes(2));

    assertThat(started.get()).isFalse();
    assertThat(cache.getIfPresent(key)).isEqualTo(original);
    await().untilTrue(started);

    assertThat(cache.asMap().put(key, updated)).isEqualTo(original);
    refresh.set(true);

    await().untilAsserted(() -> assertThat(context).removalNotifications().hasSize(2));

    assertThat(cache).containsEntry(key, updated);
    assertThat(context).stats().success(1).failures(0);
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Map.entry(key, original), Map.entry(key, refreshed))
        .exclusively();
  }

  @CheckNoEvictions
  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, removalListener = Listener.CONSUMING,
      loader = Loader.ASYNC_INCOMPLETE)
  void put_insert_discardsRefresh(LoadingCache<Int, Int> cache, CacheContext context) {
    // An explicit refresh on an absent key registers an in-flight reload; the put that inserts the
    // key must discard that refresh (so it cannot suppress later refreshes), and the stale reload
    // must not overwrite the inserted value when it completes.
    Int key = context.absentKey();
    var future = cache.refresh(key);
    assertThat(cache.policy().refreshes()).containsKey(key);

    cache.put(key, context.absentValue());
    assertThat(cache.policy().refreshes()).doesNotContainKey(key);

    future.complete(context.absentKey().negate());
    assertThat(cache).containsEntry(key, context.absentValue());
    assertThat(cache.policy().refreshes()).isEmpty();
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(context.absentKey(), context.absentKey().negate())
        .exclusively();
  }

  /* --------------- invalidate --------------- */

  @CheckNoEvictions
  @ParameterizedTest
  @SuppressWarnings("resource")
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      executor = CacheExecutor.THREADED, removalListener = Listener.CONSUMING)
  void invalidate(CacheContext context) {
    var started = new AtomicBoolean();
    var refresh = new AtomicBoolean();
    Int key = context.absentKey();
    Int original = Int.valueOf(1);
    Int refreshed = Int.valueOf(2);
    var cache = context.build((Int k) -> {
      started.set(true);
      await().untilTrue(refresh);
      return refreshed;
    });

    cache.put(key, original);
    context.ticker().advance(Duration.ofMinutes(2));

    assertThat(started.get()).isFalse();
    assertThat(cache.getIfPresent(key)).isEqualTo(original);
    await().untilTrue(started);

    cache.invalidate(key);
    refresh.set(true);

    await().until(() -> context.executor().submitted() == context.executor().completed());

    if (context.isGuava()) {
      // Guava does not protect against ABA when the entry was removed by allowing a possibly
      // stale value from being inserted.
      assertThat(cache.getIfPresent(key)).isEqualTo(refreshed);
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(key, original).exclusively();
    } else {
      // Maintain linearizability by discarding the refresh if completing after an explicit removal
      assertThat(cache.getIfPresent(key)).isNull();
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(Map.entry(key, original), Map.entry(key, refreshed))
          .exclusively();
    }
    assertThat(context).stats().success(1).failures(0);
  }

  @CheckNoEvictions
  @ParameterizedTest
  @SuppressWarnings("resource")
  @CacheSpec(implementation = Implementation.Caffeine,
      loader = Loader.ASYNC_INCOMPLETE, refreshAfterWrite = Expire.ONE_MINUTE)
  void refresh(LoadingCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    @Var int submitted;

    // trigger an automatic refresh
    submitted = context.executor().submitted();
    context.ticker().advance(Duration.ofMinutes(2));
    var value1 = cache.getIfPresent(context.absentKey());
    assertThat(value1).isEqualTo(context.absentValue());
    assertThat(context.executor().submitted()).isEqualTo(submitted + 1);
    var automatic1 = cache.policy().refreshes().get(context.absentKey());

    // return in-flight future
    var future1 = cache.refresh(context.absentKey());
    assertThat(future1).isSameInstanceAs(automatic1);
    assertThat(context.executor().submitted()).isEqualTo(submitted + 1);
    future1.complete(intern(context.absentValue().negate()));

    // trigger a new automatic refresh
    submitted = context.executor().submitted();
    context.ticker().advance(Duration.ofMinutes(2));
    var value2 = cache.getIfPresent(context.absentKey());
    assertThat(value2).isEqualTo(context.absentValue().negate());
    assertThat(context.executor().submitted()).isEqualTo(submitted + 1);
    var automatic2 = cache.policy().refreshes().get(context.absentKey());

    var future2 = cache.refresh(context.absentKey());
    assertThat(future2).isNotSameInstanceAs(future1);
    assertThat(future2).isSameInstanceAs(automatic2);
    future2.cancel(true);
  }

  /* --------------- Policy: refreshes --------------- */

  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  void refreshes(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.getIfPresent(context.firstKey());
    assertThat(cache.policy().refreshes()).hasSize(1);
    assertThat(value).isNotNull();

    var future = cache.policy().refreshes().get(context.firstKey());
    assertThat(future).isNotNull();
    requireNonNull(future);

    future.complete(Int.MAX_VALUE);
    assertThat(cache.policy().refreshes()).isExhaustivelyEmpty();
    assertThat(cache).containsEntry(context.firstKey(), Int.MAX_VALUE);
  }

  @ParameterizedTest
  @SuppressWarnings("CollectionUndefinedEquality")
  @CacheSpec(implementation = Implementation.Caffeine, loader = Loader.ASYNC_INCOMPLETE,
      refreshAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  void refreshes_nullLookup(LoadingCache<Int, Int> cache, CacheContext context) {
    context.ticker().advance(Duration.ofMinutes(2));
    var value = cache.getIfPresent(context.firstKey());
    assertThat(value).isNotNull();

    var future = requireNonNull(cache.policy().refreshes().get(context.firstKey()));
    assertThat(cache.policy().refreshes().get(null)).isNull();
    assertThat(cache.policy().refreshes().containsKey(null)).isFalse();
    assertThat(cache.policy().refreshes().containsValue(null)).isFalse();

    future.cancel(true);
  }

  /* --------------- Policy: refreshAfterWrite --------------- */

  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  void getRefreshesAfter(FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(1);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(1);
  }

  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  void setRefreshAfter_negative(FixedRefresh<Int, Int> refreshAfterWrite) {
    var duration = Duration.ofMinutes(-2);
    assertThrows(IllegalArgumentException.class, () ->
        refreshAfterWrite.setRefreshesAfter(duration));
  }

  @ParameterizedTest
  @SuppressWarnings("PreferJavaTimeOverload")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  void setRefreshAfter_zero(FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThrows(IllegalArgumentException.class, () ->
        refreshAfterWrite.setRefreshesAfter(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () ->
        refreshAfterWrite.setRefreshesAfter(0, TimeUnit.MINUTES));
  }

  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  void setRefreshAfter_excessive(FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(ChronoUnit.FOREVER.getDuration());
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  @ParameterizedTest
  @SuppressWarnings("PreferJavaTimeOverload")
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  void setRefreshesAfter(FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(2, TimeUnit.MINUTES);
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(2);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(2);
  }

  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  void setRefreshesAfter_duration(FixedRefresh<Int, Int> refreshAfterWrite) {
    refreshAfterWrite.setRefreshesAfter(Duration.ofMinutes(2));
    assertThat(refreshAfterWrite.getRefreshesAfter().toMinutes()).isEqualTo(2);
    assertThat(refreshAfterWrite.getRefreshesAfter(TimeUnit.MINUTES)).isEqualTo(2);
  }

  @ParameterizedTest
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      refreshAfterWrite = Expire.ONE_MINUTE)
  void ageOf(CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(0);
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(30);
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(refreshAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS)).hasValue(75);
  }

  @ParameterizedTest
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      refreshAfterWrite = Expire.ONE_MINUTE)
  void ageOf_duration(CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    // Truncated to seconds to ignore the LSB (nanosecond) used for refreshAfterWrite's lock
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).orElseThrow().toSeconds()).isEqualTo(0);
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).orElseThrow().toSeconds()).isEqualTo(30);
    context.ticker().advance(Duration.ofSeconds(45));
    assertThat(refreshAfterWrite.ageOf(context.firstKey()).orElseThrow().toSeconds()).isEqualTo(75);
  }

  @ParameterizedTest
  @CacheSpec(refreshAfterWrite = Expire.ONE_MINUTE)
  void ageOf_absent(CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    assertThat(refreshAfterWrite.ageOf(context.absentKey())).isEmpty();
    assertThat(refreshAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE,
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE }, expiryTime = Expire.ONE_MINUTE,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  void ageOf_expired(Cache<Int, Int> cache,
      CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    cache.put(context.absentKey(), context.absentValue());
    context.ticker().advance(Duration.ofMinutes(2));
    assertThat(refreshAfterWrite.ageOf(context.absentKey(), TimeUnit.SECONDS)).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.ONE_MINUTE)
  void ageOf_async(AsyncCache<Int, Int> cache,
      CacheContext context, FixedRefresh<Int, Int> refreshAfterWrite) {
    var future = new CompletableFuture<Int>();
    cache.put(context.absentKey(), future);
    assertThat(refreshAfterWrite.ageOf(context.absentKey()).orElseThrow())
        .isAtLeast(Duration.ofNanos(-Async.ASYNC_EXPIRY));

    future.complete(Int.valueOf(2));
    context.ticker().advance(Duration.ofSeconds(30));
    assertThat(refreshAfterWrite.ageOf(context.absentKey()).orElseThrow())
        .isIn(Range.closed(Duration.ofSeconds(30), Duration.ofSeconds(31)));
  }
}
