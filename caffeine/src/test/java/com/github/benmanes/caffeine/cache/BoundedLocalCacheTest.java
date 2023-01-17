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

import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.IDLE;
import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.PROCESSING_TO_IDLE;
import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.PROCESSING_TO_REQUIRED;
import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.REQUIRED;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.ADMIT_HASHDOS_THRESHOLD;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.EXPIRE_WRITE_TOLERANCE;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.PERCENT_MAIN_PROTECTED;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.WARN_AFTER_LOCK_WAIT_NANOS;
import static com.github.benmanes.caffeine.cache.BoundedLocalCache.WRITE_BUFFER_MAX;
import static com.github.benmanes.caffeine.cache.Node.WINDOW;
import static com.github.benmanes.caffeine.cache.RemovalCause.COLLECTED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPIRED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.RemovalCause.SIZE;
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_ACCESS;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.AFTER_WRITE;
import static com.github.benmanes.caffeine.cache.testing.CacheSpec.Expiration.VARIABLE;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Thread.State.BLOCKED;
import static java.lang.Thread.State.WAITING;
import static java.util.Locale.US;
import static java.util.function.Function.identity;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static uk.org.lidalia.slf4jext.ConventionalLevelHierarchy.WARN_LEVELS;
import static uk.org.lidalia.slf4jext.Level.ERROR;
import static uk.org.lidalia.slf4jext.Level.WARN;

import java.lang.ref.Reference;
import java.time.Duration;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.commons.lang3.mutable.MutableInt;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedPolicy.FixedExpireAfterWrite;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.PerformCleanupTask;
import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.Policy.FixedExpiration;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoEvictions;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.ConsumingRemovalListener;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.github.benmanes.caffeine.testing.Int;
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.testing.GcFinalization;
import com.google.common.util.concurrent.Uninterruptibles;

/**
 * The test cases for the implementation details of {@link BoundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(WARN)
@SuppressWarnings("GuardedBy")
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class BoundedLocalCacheTest {

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, removalListener = Listener.MOCKITO)
  public void clear_pendingWrites(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var populate = new boolean[] { true };
    Answer<?> fillWriteBuffer = invocation -> {
      while (populate[0] && cache.writeBuffer.offer(() -> {})) {
        // ignored
      }
      populate[0] = false;
      return null;
    };
    doAnswer(fillWriteBuffer)
      .when(context.removalListener())
      .onRemoval(any(), any(), any());

    cache.clear();
    assertThat(cache).isExhaustivelyEmpty();
    assertThat(cache.writeBuffer).isEmpty();
  }

  /* --------------- Maintenance --------------- */

  @Test
  @SuppressWarnings("UnusedVariable")
  public void cleanupTask_allowGc() {
    var cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder(), /* loader */ null, /* async */ false) {};
    var task = cache.drainBuffersTask;
    cache = null;

    GcFinalization.awaitClear(task.reference);
    task.run();
  }

  @Test
  @CheckMaxLogLevel(ERROR)
  public void cleanupTask_exception() {
    var expected = new RuntimeException();
    var cache = Mockito.mock(BoundedLocalCache.class);
    doThrow(expected).when(cache).performCleanUp(any());
    var task = new PerformCleanupTask(cache);
    assertThat(task.exec()).isFalse();

    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getThrowable().orElseThrow()).isSameInstanceAs(expected);
    assertThat(event.getLevel()).isEqualTo(ERROR);
  }

  @Test
  @CheckMaxLogLevel(ERROR)
  public void cleanup_exception() {
    var expected = new RuntimeException();
    var cache = Mockito.mock(BoundedLocalCache.class);
    doThrow(expected).when(cache).performCleanUp(any());
    doCallRealMethod().when(cache).cleanUp();
    cache.cleanUp();

    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getThrowable().orElseThrow()).isSameInstanceAs(expected);
    assertThat(event.getLevel()).isEqualTo(ERROR);
  }

  @Test
  public void scheduleAfterWrite() {
    var cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder(), /* loader */ null, /* async */ false) {
      @Override void scheduleDrainBuffers() {}
    };
    var transitions = Map.of(
        IDLE, REQUIRED,
        REQUIRED, REQUIRED,
        PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED,
        PROCESSING_TO_REQUIRED, PROCESSING_TO_REQUIRED);
    transitions.forEach((start, end) -> {
      cache.drainStatus = start;
      cache.scheduleAfterWrite();
      assertThat(cache.drainStatus).isEqualTo(end);
    });
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void scheduleAfterWrite_invalidDrainStatus() {
    var cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder(), /* loader */ null, /* async */ false) {};
    var valid = Set.of(IDLE, REQUIRED, PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED);
    for (;;) {
      int state = ThreadLocalRandom.current().nextInt();
      if (!valid.contains(state)) {
        cache.drainStatus = state;
        cache.scheduleAfterWrite();
        Assert.fail("Should not be valid: " + state);
      }
    }
  }

  @Test
  public void scheduleDrainBuffers() {
    var executor = Mockito.mock(Executor.class);
    var cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder().executor(executor), /* loader */ null, /* async */ false) {};
    var transitions = Map.of(
        IDLE, PROCESSING_TO_IDLE,
        REQUIRED, PROCESSING_TO_IDLE,
        PROCESSING_TO_IDLE, PROCESSING_TO_IDLE,
        PROCESSING_TO_REQUIRED, PROCESSING_TO_REQUIRED);
    transitions.forEach((start, end) -> {
      cache.drainStatus = start;
      cache.scheduleDrainBuffers();
      assertThat(cache.drainStatus).isEqualTo(end);

      if (!start.equals(end)) {
        verify(executor).execute(any());
        Mockito.reset(executor);
      }
    });
  }

  @Test
  public void rescheduleDrainBuffers() {
    var evicting = new AtomicBoolean();
    var done = new AtomicBoolean();
    var evictionListener = new RemovalListener<Int, Int>() {
      @Override public void onRemoval(Int key, Int value, RemovalCause cause) {
        evicting.set(true);
        await().untilTrue(done);
      }
    };
    var cache = asBoundedLocalCache(Caffeine.newBuilder()
        .executor(CacheExecutor.THREADED.create())
        .evictionListener(evictionListener)
        .maximumSize(0)
        .build());
    cache.put(Int.valueOf(1), Int.valueOf(1));
    await().untilTrue(evicting);

    cache.put(Int.valueOf(2), Int.valueOf(2));
    assertThat(cache.drainStatus).isEqualTo(PROCESSING_TO_REQUIRED);

    done.set(true);
    await().untilAsserted(() -> assertThat(cache.drainStatus).isAnyOf(REQUIRED, IDLE));
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL, maximumSize = Maximum.FULL,
      executor = CacheExecutor.REJECTING, executorFailure = ExecutorFailure.EXPECTED,
      removalListener = Listener.CONSUMING)
  public void scheduleDrainBuffers_rejected(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());

    assertThat(cache.drainStatus).isEqualTo(IDLE);
    assertThat(cache.writeBuffer.isEmpty()).isTrue();
    assertThat(cache.evictionLock.isLocked()).isFalse();
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void shouldDrainBuffers_invalidDrainStatus() {
    var cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder(), /* loader */ null, /* async */ false) {};
    var valid = Set.of(IDLE, REQUIRED, PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED);
    for (;;) {
      int state = ThreadLocalRandom.current().nextInt();
      if (!valid.contains(state)) {
        cache.drainStatus = state;
        cache.shouldDrainBuffers(true);
        Assert.fail("Should not be valid: " + state);
      }
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, scheduler = CacheScheduler.MOCKITO)
  public void rescheduleCleanUpIfIncomplete_complete(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    reset(context.scheduler());
    for (int status : new int[] { IDLE, PROCESSING_TO_IDLE, PROCESSING_TO_REQUIRED}) {
      cache.drainStatus = status;
      cache.rescheduleCleanUpIfIncomplete();
      verifyNoInteractions(context.scheduler());
      assertThat(cache.drainStatus).isEqualTo(status);
      assertThat(context.executor().completed()).isEqualTo(context.population().size());
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void rescheduleCleanUpIfIncomplete_incompatible(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.drainStatus = REQUIRED;
    cache.rescheduleCleanUpIfIncomplete();
    assertThat(cache.drainStatus).isEqualTo(REQUIRED);
    assertThat(context.executor().completed()).isEqualTo(context.population().size());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.DEFAULT)
  public void rescheduleCleanUpIfIncomplete_immediate(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.drainStatus = REQUIRED;
    cache.rescheduleCleanUpIfIncomplete();
    await().until(() -> cache.drainStatus, is(IDLE));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, scheduler = CacheScheduler.MOCKITO)
  public void rescheduleCleanUpIfIncomplete_notScheduled_future(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    reset(context.scheduler());
    cache.drainStatus = REQUIRED;
    cache.pacer().future = new CompletableFuture<>();

    cache.rescheduleCleanUpIfIncomplete();
    verifyNoInteractions(context.scheduler());
    await().until(() -> cache.drainStatus, is(REQUIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, scheduler = CacheScheduler.MOCKITO)
  public void rescheduleCleanUpIfIncomplete_notScheduled_locked(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    reset(context.scheduler());
    cache.drainStatus = REQUIRED;
    cache.pacer().cancel();

    var done = new AtomicBoolean();
    cache.evictionLock.lock();
    try {
      ConcurrentTestHarness.execute(() -> {
        cache.rescheduleCleanUpIfIncomplete();
        done.set(true);
      });
      await().untilTrue(done);
      verifyNoInteractions(context.scheduler());
      await().until(() -> cache.drainStatus, is(REQUIRED));
    } finally {
      cache.evictionLock.unlock();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, scheduler = CacheScheduler.MOCKITO)
  public void rescheduleCleanUpIfIncomplete_scheduled_noFuture(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    reset(context.scheduler());

    when(context.scheduler().schedule(any(), any(), anyLong(), any()))
        .thenReturn(new CompletableFuture<>());
    cache.drainStatus = REQUIRED;
    cache.pacer().cancel();

    cache.rescheduleCleanUpIfIncomplete();
    assertThat(cache.pacer().isScheduled()).isTrue();
    verify(context.scheduler()).schedule(any(), any(), anyLong(), any());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, scheduler = CacheScheduler.MOCKITO)
  public void rescheduleCleanUpIfIncomplete_scheduled_doneFuture(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    reset(context.scheduler());

    when(context.scheduler().schedule(any(), any(), anyLong(), any()))
        .thenReturn(new CompletableFuture<>());
    cache.pacer().future = DisabledFuture.INSTANCE;
    cache.drainStatus = REQUIRED;

    cache.rescheduleCleanUpIfIncomplete();
    assertThat(cache.pacer().isScheduled()).isTrue();
    verify(context.scheduler()).schedule(any(), any(), anyLong(), any());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY)
  public void afterWrite_drainFullWriteBuffer(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.drainStatus = PROCESSING_TO_IDLE;

    int[] queued = { 0 };
    Runnable pendingTask = () -> queued[0]++;

    for (int i = 0; i < WRITE_BUFFER_MAX; i++) {
      cache.afterWrite(pendingTask);
    }
    assertThat(cache.drainStatus).isEqualTo(PROCESSING_TO_REQUIRED);

    int[] triggered = { 0 };
    Runnable triggerTask = () -> triggered[0] = WRITE_BUFFER_MAX + 1;
    cache.afterWrite(triggerTask);

    assertThat(cache.drainStatus).isEqualTo(IDLE);
    assertThat(cache.evictionLock.isLocked()).isFalse();
    assertThat(queued[0]).isEqualTo(WRITE_BUFFER_MAX);
    assertThat(triggered[0]).isEqualTo(WRITE_BUFFER_MAX + 1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.DISCARDING)
  public void afterWrite_drainFullWriteBuffer_discarded(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    assertThat(cache.drainStatus).isEqualTo(PROCESSING_TO_IDLE);
    assertThat(cache.evictionLock.isLocked()).isFalse();

    int[] queued = { 1 };
    Runnable pendingTask = () -> queued[0]++;

    for (int i = 0; i < (WRITE_BUFFER_MAX - 1); i++) {
      cache.afterWrite(pendingTask);
    }
    assertThat(cache.drainStatus).isEqualTo(PROCESSING_TO_REQUIRED);

    int[] triggered = { 0 };
    Runnable triggerTask = () -> triggered[0] = WRITE_BUFFER_MAX + 1;
    cache.afterWrite(triggerTask);

    assertThat(cache.drainStatus).isEqualTo(IDLE);
    assertThat(cache.evictionLock.isLocked()).isFalse();
    assertThat(queued[0]).isEqualTo(WRITE_BUFFER_MAX);
    assertThat(triggered[0]).isEqualTo(WRITE_BUFFER_MAX + 1);
  }

  @Test @CheckMaxLogLevel(ERROR)
  public void afterWrite_exception() {
    var expected = new RuntimeException();
    var cache = new BoundedLocalCache<Object, Object>(
        Caffeine.newBuilder(), /* loader */ null, /* async */ false) {
      @Override void maintenance(Runnable task) {
        throw expected;
      }
    };

    Runnable pendingTask = () -> {};
    for (int i = 0; i < WRITE_BUFFER_MAX; i++) {
      cache.afterWrite(pendingTask);
    }
    assertThat(cache.drainStatus).isEqualTo(PROCESSING_TO_REQUIRED);

    cache.afterWrite(pendingTask);
    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getThrowable().orElseThrow()).isSameInstanceAs(expected);
    assertThat(event.getLevel()).isEqualTo(ERROR);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL, weigher = CacheWeigher.TEN)
  public void weightedSize_maintenance(BoundedLocalCache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    cache.drainStatus = REQUIRED;
    eviction.weightedSize();
    assertThat(cache.drainStatus).isEqualTo(IDLE);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.FULL)
  public void maximumSize_maintenance(BoundedLocalCache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    cache.drainStatus = REQUIRED;
    eviction.getMaximum();
    assertThat(cache.drainStatus).isEqualTo(IDLE);
  }

  /* --------------- Eviction --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.MAX_VALUE)
  public void overflow_add_one(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    long actualWeight = cache.weightedSize();
    cache.setWeightedSize(BoundedLocalCache.MAXIMUM_CAPACITY);
    cache.put(context.absentKey(), context.absentValue());

    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache.weightedSize()).isEqualTo(BoundedLocalCache.MAXIMUM_CAPACITY);

    var removed = new HashMap<>(context.original());
    removed.put(context.absentKey(), context.absentValue());
    removed.keySet().removeAll(cache.keySet());
    assertThat(context).notifications().hasSize(1);
    assertThat(context).notifications().withCause(SIZE).contains(removed).exclusively();

    // reset for validation listener
    cache.setWeightedSize(actualWeight);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.MAX_VALUE)
  public void overflow_add_many(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    long actualWeight = cache.weightedSize();
    cache.setWeightedSize(BoundedLocalCache.MAXIMUM_CAPACITY);
    cache.evictionLock.lock();
    try {
      cache.putAll(context.absent());
    } finally {
      cache.evictionLock.unlock();
    }
    cache.cleanUp();

    assertThat(cache).hasSize(context.initialSize());
    assertThat(cache.weightedSize()).isEqualTo(BoundedLocalCache.MAXIMUM_CAPACITY);

    var removed = new HashMap<>(context.original());
    removed.putAll(context.absent());
    removed.keySet().removeAll(cache.keySet());
    assertThat(context).notifications().hasSize(context.absent().size());
    assertThat(context).notifications().withCause(SIZE).contains(removed).exclusively();

    // reset for validation listener
    cache.setWeightedSize(actualWeight);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void overflow_update_one(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.setWeightedSize(BoundedLocalCache.MAXIMUM_CAPACITY);
    cache.put(context.firstKey(), Int.MAX_VALUE);

    assertThat(cache).hasSizeLessThan(1 + context.initialSize());
    assertThat(cache.weightedSize()).isAtMost(BoundedLocalCache.MAXIMUM_CAPACITY);

    var removed = new HashMap<>(context.original());
    removed.put(context.firstKey(), Int.MAX_VALUE);
    removed.keySet().removeAll(cache.keySet());
    assertThat(removed.size()).isAtLeast(1);
    assertThat(context).notifications().withCause(SIZE).contains(removed);

    // reset for validation listener
    cache.setWeightedSize(cache.data.values().stream().mapToLong(Node::getWeight).sum());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.VALUE)
  public void overflow_update_many(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var updated = Maps.asMap(context.firstMiddleLastKeys(), key -> Int.MAX_VALUE);
    cache.setWeightedSize(BoundedLocalCache.MAXIMUM_CAPACITY);
    cache.evictionLock.lock();
    try {
      cache.putAll(updated);
    } finally {
      cache.evictionLock.unlock();
    }
    cache.cleanUp();

    assertThat(cache).hasSizeLessThan(1 + context.initialSize());
    assertThat(cache.weightedSize()).isAtMost(BoundedLocalCache.MAXIMUM_CAPACITY);

    var removed = new HashMap<>(context.original());
    removed.putAll(updated);
    removed.keySet().removeAll(cache.keySet());
    assertThat(removed.size()).isAtLeast(1);
    assertThat(context).notifications().withCause(SIZE).contains(removed);

    // reset for validation listener
    cache.setWeightedSize(cache.data.values().stream().mapToLong(Node::getWeight).sum());
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY, maximumSize = Maximum.ONE)
  public void evict_alreadyRemoved(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var oldEntry = Iterables.get(context.absent().entrySet(), 0);
    var newEntry = Iterables.get(context.absent().entrySet(), 1);

    var removed = new AtomicBoolean();
    cache.put(oldEntry.getKey(), oldEntry.getValue());
    cache.evictionLock.lock();
    try {
      var lookupKey = cache.nodeFactory.newLookupKey(oldEntry.getKey());
      var node = cache.data.get(lookupKey);
      checkStatus(node, Status.ALIVE);
      ConcurrentTestHarness.execute(() -> {
        cache.put(newEntry.getKey(), newEntry.getValue());
        assertThat(cache.remove(oldEntry.getKey())).isEqualTo(oldEntry.getValue());
        removed.set(true);
      });

      await().untilAsserted(() -> assertThat(cache).doesNotContainKey(oldEntry.getKey()));
      await().untilTrue(removed);
      await().until(() -> {
        synchronized (node) {
          return !node.isAlive();
        }
      });
      checkStatus(node, Status.RETIRED);
      cache.cleanUp();

      checkStatus(node, Status.DEAD);
      assertThat(cache).containsKey(newEntry.getKey());
      assertThat(context).removalNotifications().withCause(EXPLICIT)
          .contains(oldEntry).exclusively();
    } finally {
      cache.evictionLock.unlock();
    }
  }

  enum Status { ALIVE, RETIRED, DEAD }

  static void checkStatus(Node<Int, Int> node, Status expected) {
    synchronized (node) {
      assertThat(node.isAlive()).isEqualTo(expected == Status.ALIVE);
      assertThat(node.isRetired()).isEqualTo(expected == Status.RETIRED);
      assertThat(node.isDead()).isEqualTo(expected == Status.DEAD);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.TEN, weigher = CacheWeigher.DISABLED)
  public void evict_wtinylfu(Cache<Int, Int> cache, CacheContext context) {
    // Enforce full initialization of internal structures; clear sketch
    asBoundedLocalCache(cache).frequencySketch().ensureCapacity(context.maximumSize());

    for (int i = 0; i < 10; i++) {
      cache.put(Int.valueOf(i), Int.valueOf(-i));
    }

    checkContainsInOrder(cache,
        /* expect */ Int.listOf(9, 0, 1, 2, 3, 4, 5, 6, 7, 8));

    // re-order
    checkReorder(cache, /* keys */ Int.listOf(0, 1, 2),
        /* expect */ Int.listOf(9, 3, 4, 5, 6, 7, 8, 0, 1, 2));

    // evict 9, 10, 11
    checkEvict(cache, /* keys */ Int.listOf(10, 11, 12),
        /* expect */ Int.listOf(12, 3, 4, 5, 6, 7, 8, 0, 1, 2));

    // re-order
    checkReorder(cache, /* keys */ Int.listOf(6, 7, 8),
        /* expect */ Int.listOf(12, 3, 4, 5, 0, 1, 2, 6, 7, 8));

    // evict 12, 13, 14
    checkEvict(cache, /* keys */ Int.listOf(13, 14, 15),
        /* expect */ Int.listOf(15, 3, 4, 5, 0, 1, 2, 6, 7, 8));

    assertThat(context).stats().evictions(6);
  }

  private void checkReorder(Cache<Int, Int> cache, List<Int> keys, List<Int> expect) {
    keys.forEach(cache::getIfPresent);
    checkContainsInOrder(cache, expect);
  }

  private void checkEvict(Cache<Int, Int> cache, List<Int> keys, List<Int> expect) {
    keys.forEach(i -> cache.put(i, i));
    checkContainsInOrder(cache, expect);
  }

  private void checkContainsInOrder(Cache<Int, Int> cache, List<Int> expect) {
    var evictionOrder = cache.policy().eviction().orElseThrow().coldest(Integer.MAX_VALUE).keySet();
    assertThat(cache).containsExactlyKeys(expect);
    assertThat(evictionOrder).containsExactlyElementsIn(expect).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      removalListener = Listener.CONSUMING)
  public void evict_candidate_lru(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.setMainProtectedMaximum(0);
    cache.setWindowMaximum(context.maximumSize());
    for (int i = 0; i < context.maximumSize(); i++) {
      cache.put(Int.valueOf(i), Int.valueOf(i));
    }

    var expected = cache.accessOrderWindowDeque().stream()
        .map(Node::getKey).collect(toImmutableList());
    cache.setWindowMaximum(0L);
    cache.evictFromWindow();

    var actual = cache.accessOrderProbationDeque().stream()
        .map(Node::getKey).collect(toImmutableList());
    assertThat(actual).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      removalListener = Listener.CONSUMING)
  public void evict_victim_lru(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.setWindowMaximum(0);
    cache.evictFromWindow();

    var expected = FluentIterable
        .from(cache.accessOrderProbationDeque())
        .append(cache.accessOrderProtectedDeque())
        .transform(Node::getKey).toList();
    cache.setMaximumSize(0L);
    cache.cleanUp();

    var listener = (ConsumingRemovalListener<Int, Int>) context.removalListener();
    var actual = listener.removed().stream().map(Map.Entry::getKey).collect(toImmutableList());
    assertThat(actual).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      removalListener = Listener.CONSUMING)
  public void evict_window_candidates(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.setWindowMaximum(context.maximumSize() / 2);
    cache.setMainProtectedMaximum(0);

    for (int i = 0; i < context.maximumSize(); i++) {
      cache.put(Int.valueOf(i), Int.valueOf(i));
    }
    Arrays.fill(cache.frequencySketch().table, 0L);

    var expected = cache.accessOrderWindowDeque().stream()
        .map(Node::getKey).collect(toImmutableList());
    cache.setMaximum(context.maximumSize() / 2);
    cache.setWindowMaximum(0);
    cache.evictEntries();

    var listener = (ConsumingRemovalListener<Int, Int>) context.removalListener();
    var actual = listener.removed().stream().map(Map.Entry::getKey).collect(toImmutableList());
    assertThat(actual).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      removalListener = Listener.CONSUMING)
  public void evict_window_fallback(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.setWindowMaximum(context.maximumSize() / 2);
    cache.setMainProtectedMaximum(0);

    for (int i = 0; i < context.maximumSize(); i++) {
      cache.put(Int.valueOf(i), Int.valueOf(i));
    }
    Arrays.fill(cache.frequencySketch().table, 0L);

    var expected = cache.accessOrderWindowDeque().stream()
        .map(Node::getKey).collect(toImmutableList());
    cache.setMaximum(context.maximumSize() / 2);
    cache.evictEntries();

    var listener = (ConsumingRemovalListener<Int, Int>) context.removalListener();
    var actual = listener.removed().stream().map(Map.Entry::getKey).collect(toImmutableList());
    assertThat(actual).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      removalListener = Listener.CONSUMING)
  public void evict_candidateIsVictim(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.setMainProtectedMaximum(context.maximumSize() / 2);
    cache.setWindowMaximum(context.maximumSize() / 2);

    for (int i = 0; i < context.maximumSize(); i++) {
      cache.put(Int.valueOf(i), Int.valueOf(i));
    }
    while (!cache.accessOrderProbationDeque().isEmpty()) {
      var node = cache.accessOrderProbationDeque().removeFirst();
      cache.accessOrderProtectedDeque().offerLast(node);
      node.makeMainProtected();
    }
    Arrays.fill(cache.frequencySketch().table, 0L);
    cache.setMainProtectedWeightedSize(context.maximumSize() - cache.windowWeightedSize());

    var expected = FluentIterable
        .from(cache.accessOrderWindowDeque())
        .append(cache.accessOrderProbationDeque())
        .append(cache.accessOrderProtectedDeque())
        .transform(Node::getKey).toList();
    cache.setMainProtectedMaximum(0L);
    cache.setWindowMaximum(0L);
    cache.setMaximum(0L);
    cache.evictEntries();

    var listener = (ConsumingRemovalListener<Int, Int>) context.removalListener();
    var actual = listener.removed().stream().map(Map.Entry::getKey).collect(toImmutableList());
    assertThat(actual).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      removalListener = Listener.CONSUMING)
  public void evict_toZero(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    for (int i = 0; i < context.maximumSize(); i++) {
      cache.put(Int.valueOf(i), Int.valueOf(i));
    }
    Arrays.fill(cache.frequencySketch().table, 0L);

    var expected = FluentIterable
        .from(cache.accessOrderWindowDeque())
        .append(cache.accessOrderProbationDeque())
        .append(cache.accessOrderProtectedDeque())
        .transform(Node::getKey).toList();
    cache.setMaximumSize(0);
    cache.evictEntries();

    var listener = (ConsumingRemovalListener<Int, Int>) context.removalListener();
    var actual = listener.removed().stream().map(Map.Entry::getKey).collect(toImmutableList());
    assertThat(actual).containsExactlyElementsIn(expected).inOrder();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED)
  public void evict_retired_candidate(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.evictionLock.lock();
    try {
      var expected = cache.accessOrderWindowDeque().peekFirst();
      var key = expected.getKey();

      ConcurrentTestHarness.execute(() -> cache.remove(key));
      await().until(() -> !cache.containsKey(key));
      assertThat(expected.isRetired()).isTrue();

      cache.setWindowMaximum(cache.windowMaximum() - 1);
      cache.setMaximum(context.maximumSize() - 1);
      cache.evictEntries();

      assertThat(expected.isDead()).isTrue();
      await().untilAsserted(() -> assertThat(cache).hasSize(cache.maximum()));
    } finally {
      cache.evictionLock.unlock();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED)
  public void evict_retired_victim(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.evictionLock.lock();
    try {
      var expected = cache.accessOrderProbationDeque().peekFirst();
      var key = expected.getKey();

      ConcurrentTestHarness.execute(() -> cache.remove(key));
      await().until(() -> !cache.containsKey(key));
      assertThat(expected.isRetired()).isTrue();

      cache.setWindowMaximum(cache.windowMaximum() - 1);
      cache.setMaximum(context.maximumSize() - 1);
      cache.evictEntries();

      assertThat(expected.isDead()).isTrue();
      await().untilAsserted(() -> assertThat(cache).hasSize(cache.maximum()));
    } finally {
      cache.evictionLock.unlock();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.VALUE)
  public void evict_zeroWeight(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    for (int i = 0; i < context.maximumSize(); i++) {
      cache.put(Int.valueOf(i), Int.valueOf(1));
      cache.get(Int.valueOf(i - 1));
    }
    cache.put(cache.accessOrderWindowDeque().peekFirst().getKey(), Int.valueOf(0));
    cache.put(cache.accessOrderProbationDeque().peekFirst().getKey(), Int.valueOf(0));

    cache.setMaximumSize(0);
    cache.evictEntries();
    assertThat(cache).hasSize(2);
    assertThat(cache.weightedSize()).isEqualTo(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void evict_admit(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.frequencySketch().ensureCapacity(context.maximumSize());
    Int candidate = Int.valueOf(0);
    Int victim = Int.valueOf(1);

    // Prefer victim if tie
    assertThat(cache.admit(candidate, victim)).isFalse();

    // Prefer candidate if more popular
    cache.frequencySketch().increment(candidate);
    assertThat(cache.admit(candidate, victim)).isTrue();

    // Prefer victim if more popular
    for (int i = 0; i < 15; i++) {
      cache.frequencySketch().increment(victim);
      assertThat(cache.admit(candidate, victim)).isFalse();
    }

    // Admit a small, random percentage of warm candidates to protect against hash flooding
    while (cache.frequencySketch().frequency(candidate) < ADMIT_HASHDOS_THRESHOLD) {
      cache.frequencySketch().increment(candidate);
    }
    int allow = 0;
    int reject = 0;
    for (int i = 0; i < 1_000; i++) {
      if (cache.admit(candidate, victim)) {
        allow++;
      } else {
        reject++;
      }
    }
    assertThat(allow).isGreaterThan(0);
    assertThat(reject).isGreaterThan(allow);
    assertThat(100.0 * allow / (allow + reject)).isIn(Range.open(0.2, 2.0));
  }

  @Test(groups = "isolated")
  public void evict_update() {
    Int key = Int.valueOf(0);
    Int oldValue = Int.valueOf(1);
    Int newValue = Int.valueOf(2);

    var evictor = Thread.currentThread();
    var started = new AtomicBoolean();
    var writing = new AtomicBoolean();
    var evictedValue = new AtomicReference<Int>();
    var previousValue = new AtomicReference<Int>();
    var removedValues = new AtomicReference<Int>(Int.valueOf(0));

    RemovalListener<Int, Int> evictionListener =
        (k, v, cause) -> evictedValue.set(v);
    RemovalListener<Int, Int> removalListener =
        (k, v, cause) -> removedValues.accumulateAndGet(v, Int::add);

    var cache = Caffeine.newBuilder()
        .executor(CacheExecutor.DIRECT.create())
        .evictionListener(evictionListener)
        .removalListener(removalListener)
        .maximumSize(100)
        .build();
    var localCache = asBoundedLocalCache(cache);
    cache.put(key, oldValue);
    started.set(true);

    ConcurrentTestHarness.execute(() -> {
      localCache.compute(key, (k, v) -> {
        if (started.get()) {
          writing.set(true);
          await().untilAsserted(() -> assertThat(evictor.getState()).isEqualTo(BLOCKED));
        }
        previousValue.set(v);
        return newValue;
      });
    });
    await().untilTrue(writing);

    var node = localCache.data.values().iterator().next();
    localCache.evictEntry(node, RemovalCause.SIZE, 0);

    await().untilAtomic(evictedValue, is(newValue));
    await().untilAtomic(previousValue, is(oldValue));
    await().untilAtomic(removedValues, is(oldValue.add(newValue)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.TEN, weigher = CacheWeigher.VALUE,
      initialCapacity = InitialCapacity.EXCESSIVE, removalListener = Listener.CONSUMING)
  public void evict_update_entryTooBig_window(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.put(Int.valueOf(9), Int.valueOf(9));
    cache.put(Int.valueOf(1), Int.valueOf(1));

    var lookupKey = cache.nodeFactory.newLookupKey(Int.valueOf(1));
    assertThat(cache.data.get(lookupKey).inWindow()).isTrue();
    cache.put(Int.valueOf(1), Int.valueOf(20));

    assertThat(cache.weightedSize()).isAtMost(context.maximumSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Int.valueOf(1), Int.valueOf(1));
    assertThat(context).removalNotifications().withCause(SIZE)
        .contains(Int.valueOf(1), Int.valueOf(20));
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(context).evictionNotifications().withCause(SIZE)
        .contains(Int.valueOf(1), Int.valueOf(20)).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.TEN, weigher = CacheWeigher.VALUE,
      initialCapacity = InitialCapacity.EXCESSIVE, removalListener = Listener.CONSUMING)
  public void evict_update_entryTooBig_probation(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    for (int i = 1; i <= 10; i++) {
      cache.put(Int.valueOf(i), Int.valueOf(1));
    }

    var lookupKey = cache.nodeFactory.newLookupKey(Int.valueOf(1));
    assertThat(cache.data.get(lookupKey).inMainProbation()).isTrue();
    cache.put(Int.valueOf(1), Int.valueOf(20));

    assertThat(cache.weightedSize()).isAtMost(context.maximumSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Int.valueOf(1), Int.valueOf(1));
    assertThat(context).removalNotifications().withCause(SIZE)
        .contains(Int.valueOf(1), Int.valueOf(20));
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(context).evictionNotifications().withCause(SIZE)
        .contains(Int.valueOf(1), Int.valueOf(20)).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.TEN, weigher = CacheWeigher.VALUE,
      initialCapacity = InitialCapacity.EXCESSIVE, removalListener = Listener.CONSUMING)
  public void evict_update_entryTooBig_protected(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    for (int i = 1; i <= 10; i++) {
      cache.put(Int.valueOf(i), Int.valueOf(1));
      cache.get(Int.valueOf(1));
    }
    cache.cleanUp();

    var lookupKey = cache.nodeFactory.newLookupKey(Int.valueOf(1));
    assertThat(cache.data.get(lookupKey).inMainProtected()).isTrue();
    cache.put(Int.valueOf(1), Int.valueOf(20));

    assertThat(cache.weightedSize()).isAtMost(context.maximumSize());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Int.valueOf(1), Int.valueOf(1));
    assertThat(context).removalNotifications().withCause(SIZE)
        .contains(Int.valueOf(1), Int.valueOf(20));
    assertThat(context).removalNotifications().hasSize(2);
    assertThat(context).evictionNotifications().withCause(SIZE)
        .contains(Int.valueOf(1), Int.valueOf(20)).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      values = {ReferenceType.WEAK, ReferenceType.SOFT}, removalListener = Listener.CONSUMING)
  public void evict_resurrect_collected(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    Int oldValue = Int.valueOf(2);
    Int newValue = Int.valueOf(3);

    cache.put(key, oldValue);
    var node = cache.data.get(cache.referenceKey(key));
    @SuppressWarnings("unchecked")
    var ref = (Reference<Int>) node.getValueReference();
    ref.enqueue();

    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var evictor = new AtomicReference<Thread>();
    cache.compute(key, (k, v) -> {
      assertThat(v).isNull();
      ConcurrentTestHarness.execute(() -> {
        evictor.set(Thread.currentThread());
        started.set(true);
        cache.cleanUp();
        done.set(true);
      });
      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(evictor.get().getState()));

      return newValue;
    });
    await().untilTrue(done);

    assertThat(node.getValue()).isEqualTo(newValue);
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(key, null).exclusively();
  }

  @CheckNoEvictions
  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, maximumSize = Maximum.UNREACHABLE,
      weigher = CacheWeigher.COLLECTION)
  public void evict_resurrect_weight(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    var value = intern(List.of(key));
    cache.put(key, value);

    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var evictor = new AtomicReference<Thread>();
    cache.asMap().compute(key, (k, v) -> {
      ConcurrentTestHarness.execute(() -> {
        evictor.set(Thread.currentThread());
        started.set(true);
        cache.policy().eviction().orElseThrow().setMaximum(0);
        done.set(true);
      });

      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(evictor.get().getState()));

      return List.of();
    });
    await().untilTrue(done);

    assertThat(cache).containsEntry(key, List.of());
    assertThat(context).removalNotifications().withCause(REPLACED)
        .contains(Map.entry(key, value)).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void evict_resurrect_expireAfter(Cache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.put(key, key);

    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var evictor = new AtomicReference<Thread>();
    context.ticker().advance(Duration.ofHours(1));
    cache.asMap().compute(key, (k, v) -> {
      ConcurrentTestHarness.execute(() -> {
        evictor.set(Thread.currentThread());
        started.set(true);
        cache.cleanUp();
        done.set(true);
      });

      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(evictor.get().getState()));
      return key.negate();
    });
    await().untilTrue(done);

    assertThat(cache).containsEntry(key, key.negate());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(key, key).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, expireAfterAccess = Expire.FOREVER)
  public void evict_resurrect_expireAfterAccess(Cache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.put(key, key);

    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var evictor = new AtomicReference<Thread>();
    context.ticker().advance(Duration.ofMinutes(1));
    cache.asMap().compute(key, (k, v) -> {
      ConcurrentTestHarness.execute(() -> {
        evictor.set(Thread.currentThread());
        started.set(true);
        cache.policy().expireAfterAccess().orElseThrow().setExpiresAfter(Duration.ZERO);
        done.set(true);
      });

      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(evictor.get().getState()));
      cache.policy().expireAfterAccess().orElseThrow().setExpiresAfter(Duration.ofHours(1));
      return v;
    });
    await().untilTrue(done);

    assertThat(cache).containsEntry(key, key);
    assertThat(context).notifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, expireAfterWrite = Expire.FOREVER)
  public void evict_resurrect_expireAfterWrite(Cache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.put(key, key);

    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var evictor = new AtomicReference<Thread>();
    context.ticker().advance(Duration.ofMinutes(1));
    cache.asMap().compute(key, (k, v) -> {
      ConcurrentTestHarness.execute(() -> {
        evictor.set(Thread.currentThread());
        started.set(true);
        cache.policy().expireAfterWrite().orElseThrow().setExpiresAfter(Duration.ZERO);
        done.set(true);
      });

      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(evictor.get().getState()));
      cache.policy().expireAfterWrite().orElseThrow().setExpiresAfter(Duration.ofHours(1));
      return v;
    });
    await().untilTrue(done);

    assertThat(cache).containsEntry(key, key);
    assertThat(context).notifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, implementation = Implementation.Caffeine,
      population = Population.EMPTY, expireAfterWrite = Expire.ONE_MINUTE)
  public void evict_resurrect_expireAfterWrite_entry(Cache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.put(key, key);

    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var evictor = new AtomicReference<Thread>();
    context.ticker().advance(Duration.ofHours(1));
    cache.asMap().compute(key, (k, v) -> {
      ConcurrentTestHarness.execute(() -> {
        evictor.set(Thread.currentThread());
        started.set(true);
        cache.cleanUp();
        done.set(true);
      });

      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(evictor.get().getState()));
      return key.negate();
    });
    await().untilTrue(done);

    assertThat(cache).containsEntry(key, key.negate());
    assertThat(context).notifications().withCause(EXPIRED)
        .contains(key, key).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      expiry = CacheExpiry.CREATE, expiryTime = Expire.ONE_MINUTE)
  public void evict_resurrect_expireAfterVar(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    Int key = Int.valueOf(1);
    cache.put(key, key);
    var node = cache.data.get(cache.referenceKey(key));

    var started = new AtomicBoolean();
    var done = new AtomicBoolean();
    var evictor = new AtomicReference<Thread>();
    synchronized (node) {
      context.ticker().advance(Duration.ofHours(1));
      ConcurrentTestHarness.execute(() -> {
        evictor.set(Thread.currentThread());
        started.set(true);
        cache.cleanUp();
        done.set(true);
      });

      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(evictor.get().getState()));
      node.setVariableTime(context.ticker().read() + TimeUnit.DAYS.toNanos(1));
    }
    await().untilTrue(done);

    assertThat(cache).containsEntry(key, key);
    assertThat(context).notifications().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      keys = ReferenceType.WEAK, removalListener = Listener.CONSUMING)
  public void evict_collected_candidate(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var candidate = cache.accessOrderWindowDeque().getFirst();
    var value = candidate.getValue();

    @SuppressWarnings("unchecked")
    var keyReference = (WeakKeyReference<Int>) candidate.getKeyReference();
    keyReference.clear();

    cache.put(context.absentKey(), context.absentValue());
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(null, value).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      keys = ReferenceType.WEAK, removalListener = Listener.CONSUMING)
  public void evict_collected_victim(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var victim = cache.accessOrderProbationDeque().getFirst();
    var value = victim.getValue();

    @SuppressWarnings("unchecked")
    var keyReference = (WeakKeyReference<Int>) victim.getKeyReference();
    keyReference.clear();

    cache.setMaximumSize(cache.size() - 1);
    cache.cleanUp();

    assertThat(context).notifications().withCause(COLLECTED)
        .contains(null, value).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onGet(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var first = firstBeforeAccess(cache, context);
    updateRecency(cache, context, () -> cache.get(first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onPutIfAbsent(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var first = firstBeforeAccess(cache, context);
    updateRecency(cache, context, () -> cache.putIfAbsent(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onPut(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var first = firstBeforeAccess(cache, context);
    updateRecency(cache, context, () -> cache.put(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onReplace(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var first = firstBeforeAccess(cache, context);
    updateRecency(cache, context, () -> cache.replace(first.getKey(), first.getKey()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL, maximumSize = Maximum.FULL)
  public void updateRecency_onReplaceConditionally(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var first = firstBeforeAccess(cache, context);
    Int value = first.getValue();

    updateRecency(cache, context, () -> cache.replace(first.getKey(), value, value));
  }

  private static Node<Int, Int> firstBeforeAccess(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    return context.isZeroWeighted()
        ? cache.accessOrderWindowDeque().peek()
        : cache.accessOrderProbationDeque().peek();
  }

  private static void updateRecency(BoundedLocalCache<Int, Int> cache,
      CacheContext context, Runnable operation) {
    var first = firstBeforeAccess(cache, context);

    operation.run();
    cache.maintenance(/* ignored */ null);

    if (context.isZeroWeighted()) {
      assertThat(cache.accessOrderWindowDeque().peekFirst()).isNotEqualTo(first);
      assertThat(cache.accessOrderWindowDeque().peekLast()).isEqualTo(first);
    } else {
      assertThat(cache.accessOrderProbationDeque().peekFirst()).isNotEqualTo(first);
      assertThat(cache.accessOrderProtectedDeque().peekLast()).isEqualTo(first);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void exceedsMaximumBufferSize_onRead(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var dummy = cache.nodeFactory.newNode(
        new WeakKeyReference<>(null, null), null, null, 1, 0);
    cache.frequencySketch().ensureCapacity(1);

    var buffer = cache.readBuffer;
    for (int i = 0; i < BoundedBuffer.BUFFER_SIZE; i++) {
      buffer.offer(dummy);
    }
    assertThat(buffer.offer(dummy)).isEqualTo(Buffer.FULL);

    cache.afterRead(dummy, 0, /* recordHit */ true);
    assertThat(buffer.offer(dummy)).isNotEqualTo(Buffer.FULL);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void exceedsMaximumBufferSize_onWrite(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var ran = new boolean[1];
    cache.afterWrite(() -> ran[0] = true);
    assertThat(ran[0]).isTrue();

    assertThat(cache.writeBuffer).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      expiry = CacheExpiry.DISABLED, keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void fastpath(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.skipReadBuffer()).isTrue();

    for (int i = 0; i < (context.maximumSize() / 2) - 1; i++) {
      cache.put(Int.valueOf(i), Int.valueOf(-i));
    }
    assertThat(cache.skipReadBuffer()).isTrue();

    cache.put(Int.valueOf(-1), Int.valueOf(-1));
    assertThat(cache.skipReadBuffer()).isFalse();
    assertThat(cache.get(Int.valueOf(0))).isNotNull();
    assertThat(cache.readBuffer.writes()).isEqualTo(1);

    cache.cleanUp();
    assertThat(cache.readBuffer.reads()).isEqualTo(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL, maximumSize = Maximum.FULL)
  public void drain_onRead(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var buffer = cache.readBuffer;
    for (int i = 0; i < BoundedBuffer.BUFFER_SIZE; i++) {
      cache.get(context.firstKey());
    }

    long pending = buffer.size();
    assertThat(buffer.writes()).isEqualTo(pending);
    assertThat(pending).isEqualTo(BoundedBuffer.BUFFER_SIZE);

    cache.get(context.firstKey());
    assertThat(buffer.size()).isEqualTo(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL, maximumSize = Maximum.FULL)
  public void drain_onRead_absent(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var buffer = cache.readBuffer;
    cache.get(context.firstKey());
    assertThat(buffer.size()).isEqualTo(1);

    assertThat(cache.get(context.absentKey())).isNull();
    assertThat(buffer.size()).isEqualTo(1);

    cache.drainStatus = REQUIRED;
    assertThat(cache.get(context.absentKey())).isNull();
    assertThat(buffer.size()).isEqualTo(0);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_onWrite(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.put(Int.valueOf(1), Int.valueOf(1));

    int size = cache.accessOrderWindowDeque().size() + cache.accessOrderProbationDeque().size();
    assertThat(cache.writeBuffer).isEmpty();
    assertThat(size).isEqualTo(1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_nonblocking(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var done = new AtomicBoolean();
    Runnable task = () -> {
      cache.setDrainStatusRelease(REQUIRED);
      cache.scheduleDrainBuffers();
      done.set(true);
    };
    cache.evictionLock.lock();
    try {
      ConcurrentTestHarness.execute(task);
      await().untilTrue(done);
    } finally {
      cache.evictionLock.unlock();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_blocksClear(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    checkDrainBlocks(cache, cache::clear);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_blocksOrderedMap(BoundedLocalCache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    checkDrainBlocks(cache, () -> eviction.coldest(((int) context.maximumSize())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY, maximumSize = Maximum.FULL)
  public void drain_blocksCapacity(BoundedLocalCache<Int, Int> cache,
      CacheContext context, Eviction<Int, Int> eviction) {
    checkDrainBlocks(cache, () -> eviction.setMaximum(0));
  }

  void checkDrainBlocks(BoundedLocalCache<Int, Int> cache, Runnable task) {
    var done = new AtomicBoolean();
    var lock = cache.evictionLock;
    lock.lock();
    try {
      ConcurrentTestHarness.execute(() -> {
        cache.setDrainStatusRelease(REQUIRED);
        task.run();
        done.set(true);
      });
      await().until(lock::hasQueuedThreads);
    } finally {
      lock.unlock();
    }
    await().untilTrue(done);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = {CacheWeigher.DISABLED, CacheWeigher.TEN})
  public void adapt_increaseWindow(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    prepareForAdaption(cache, context, /* make frequency-bias */ false);

    int sampleSize = cache.frequencySketch().sampleSize;
    long protectedSize = cache.mainProtectedWeightedSize();
    long protectedMaximum = cache.mainProtectedMaximum();
    long windowSize = cache.windowWeightedSize();
    long windowMaximum = cache.windowMaximum();

    adapt(cache, sampleSize);

    assertThat(cache.mainProtectedWeightedSize()).isLessThan(protectedSize);
    assertThat(cache.mainProtectedMaximum()).isLessThan(protectedMaximum);
    assertThat(cache.windowWeightedSize()).isGreaterThan(windowSize);
    assertThat(cache.windowMaximum()).isGreaterThan(windowMaximum);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.FULL,
      maximumSize = Maximum.FULL, weigher = {CacheWeigher.DISABLED, CacheWeigher.TEN})
  public void adapt_decreaseWindow(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    prepareForAdaption(cache, context, /* make recency-bias */ true);

    int sampleSize = cache.frequencySketch().sampleSize;
    long protectedSize = cache.mainProtectedWeightedSize();
    long protectedMaximum = cache.mainProtectedMaximum();
    long windowSize = cache.windowWeightedSize();
    long windowMaximum = cache.windowMaximum();

    adapt(cache, sampleSize);

    assertThat(cache.mainProtectedWeightedSize()).isGreaterThan(protectedSize);
    assertThat(cache.mainProtectedMaximum()).isGreaterThan(protectedMaximum);
    assertThat(cache.windowWeightedSize()).isLessThan(windowSize);
    assertThat(cache.windowMaximum()).isLessThan(windowMaximum);
  }

  private void prepareForAdaption(BoundedLocalCache<Int, Int> cache,
      CacheContext context, boolean recencyBias) {
    cache.setStepSize((recencyBias ? 1 : -1) * Math.abs(cache.stepSize()));
    cache.setWindowMaximum((long) (0.5 * context.maximumWeightOrSize()));
    cache.setMainProtectedMaximum((long)
        (PERCENT_MAIN_PROTECTED * (context.maximumWeightOrSize() - cache.windowMaximum())));

    // Fill window and main spaces
    cache.clear();
    cache.putAll(context.original());
    cache.keySet().forEach(cache::get);
    cache.keySet().forEach(cache::get);
  }

  private void adapt(BoundedLocalCache<Int, Int> cache, int sampleSize) {
    cache.setPreviousSampleHitRate(0.80);
    cache.setMissesInSample(sampleSize / 2);
    cache.setHitsInSample(sampleSize - cache.missesInSample());
    cache.climb();

    // Fill main protected space
    cache.keySet().forEach(cache::get);
  }

  /* --------------- Expiration --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      initialCapacity = InitialCapacity.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void put_expireTolerance_expireAfterWrite(
      BoundedLocalCache<Int, Int> cache, CacheContext context) {
    boolean mayCheckReads = context.isStrongKeys() && context.isStrongValues()
        && (cache.readBuffer != Buffer.<Node<Int, Int>>disabled());

    cache.put(Int.valueOf(1), Int.valueOf(1));
    assertThat(cache.writeBuffer.producerIndex).isEqualTo(2);

    // If within the tolerance, treat the update as a read
    cache.put(Int.valueOf(1), Int.valueOf(2));
    if (mayCheckReads) {
      assertThat(cache.readBuffer.reads()).isEqualTo(0);
      assertThat(cache.readBuffer.writes()).isEqualTo(1);
    }
    assertThat(cache.writeBuffer.producerIndex).isEqualTo(2);

    // If exceeds the tolerance, treat the update as a write
    context.ticker().advance(EXPIRE_WRITE_TOLERANCE + 1, TimeUnit.NANOSECONDS);
    cache.put(Int.valueOf(1), Int.valueOf(3));
    if (mayCheckReads) {
      assertThat(cache.readBuffer.reads()).isEqualTo(1);
      assertThat(cache.readBuffer.writes()).isEqualTo(1);
    }
    assertThat(cache.writeBuffer.producerIndex).isEqualTo(4);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void put_expireTolerance_expiry(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.put(Int.valueOf(1), Int.valueOf(1));
    assertThat(cache.writeBuffer.producerIndex).isEqualTo(2);

    // If within the tolerance, treat the update as a read
    cache.put(Int.valueOf(1), Int.valueOf(2));
    assertThat(cache.readBuffer.reads()).isEqualTo(0);
    assertThat(cache.readBuffer.writes()).isEqualTo(1);
    assertThat(cache.writeBuffer.producerIndex).isEqualTo(2);

    // If exceeds the tolerance, treat the update as a write
    context.ticker().advance(EXPIRE_WRITE_TOLERANCE + 1, TimeUnit.NANOSECONDS);
    cache.put(Int.valueOf(1), Int.valueOf(3));
    assertThat(cache.readBuffer.reads()).isEqualTo(1);
    assertThat(cache.readBuffer.writes()).isEqualTo(1);
    assertThat(cache.writeBuffer.producerIndex).isEqualTo(4);

    // If the expiration time reduces by more than the tolerance, treat the update as a write
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Expire.ONE_MILLISECOND.timeNanos());
    cache.put(Int.valueOf(1), Int.valueOf(4));
    assertThat(cache.readBuffer.reads()).isEqualTo(1);
    assertThat(cache.readBuffer.writes()).isEqualTo(1);
    assertThat(cache.writeBuffer.producerIndex).isEqualTo(6);

    // If the expiration time increases by more than the tolerance, treat the update as a write
    when(context.expiry().expireAfterUpdate(any(), any(), anyLong(), anyLong()))
        .thenReturn(Expire.FOREVER.timeNanos());
    cache.put(Int.valueOf(1), Int.valueOf(4));
    assertThat(cache.readBuffer.reads()).isEqualTo(1);
    assertThat(cache.readBuffer.writes()).isEqualTo(1);
    assertThat(cache.writeBuffer.producerIndex).isEqualTo(8);
  }

  @CheckMaxLogLevel(WARN)
  @Test(dataProvider = "caches", groups = "isolated")
  @CacheSpec(population = Population.EMPTY,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      refreshAfterWrite = Expire.DISABLED, expiry = CacheExpiry.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      compute = Compute.SYNC, loader = Loader.DISABLED, stats = Stats.DISABLED,
      removalListener = Listener.DISABLED, evictionListener = Listener.DISABLED,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_warnIfEvictionBlocked(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var testLogger = new AtomicReference<TestLogger>();
    var thread = new AtomicReference<Thread>();
    var done = new AtomicBoolean();
    cache.evictionLock.lock();
    try {
      ConcurrentTestHarness.execute(() -> {
        var logger = TestLoggerFactory.getTestLogger(BoundedLocalCache.class);
        logger.setEnabledLevels(WARN_LEVELS);
        thread.set(Thread.currentThread());
        testLogger.set(logger);

        for (int i = 0; true; i++) {
          if (done.get()) {
            return;
          }
          cache.put(Int.valueOf(i), Int.valueOf(i));
        }
      });

      var halfWaitTime = Duration.ofNanos(WARN_AFTER_LOCK_WAIT_NANOS / 2);
      await().until(cache.evictionLock::hasQueuedThreads);
      thread.get().interrupt();

      Uninterruptibles.sleepUninterruptibly(halfWaitTime);
      assertThat(cache.evictionLock.hasQueuedThreads()).isTrue();
      assertThat(testLogger.get().getAllLoggingEvents()).isEmpty();

      Uninterruptibles.sleepUninterruptibly(halfWaitTime.plusMillis(500));
      await().until(() -> !testLogger.get().getAllLoggingEvents().isEmpty());

      assertThat(cache.evictionLock.hasQueuedThreads()).isTrue();
    } finally {
      done.set(true);
      cache.evictionLock.unlock();
    }
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void put_spinToCompute(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    var node = cache.data.get(context.absentKey());
    node.retire();

    cache.data.compute(context.absentKey(), (k, n) -> {
      var writer = new AtomicReference<Thread>();
      ConcurrentTestHarness.execute(() -> {
        writer.set(Thread.currentThread());
        cache.put(context.absentKey(), context.absentKey());
      });

      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().untilAtomic(writer, is(not(nullValue())));
      await().until(() -> threadState.contains(writer.get().getState()));

      return null;
    });

    await().untilAsserted(() ->
        assertThat(cache).containsEntry(context.absentKey(), context.absentKey()));
    cache.afterWrite(cache.new RemovalTask(node));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      expiry = CacheExpiry.MOCKITO, expiryTime = Expire.ONE_MINUTE)
  public void putIfAbsent_expireAfterRead(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var node = cache.data.get(cache.nodeFactory.newLookupKey(context.firstKey()));
    context.ticker().advance(1, TimeUnit.HOURS);
    var result = new AtomicReference<Int>();
    long currentDuration = 1;

    synchronized (node) {
      var started = new AtomicBoolean();
      var thread = new AtomicReference<Thread>();
      ConcurrentTestHarness.execute(() -> {
        thread.set(Thread.currentThread());
        started.set(true);
        var value = cache.putIfAbsent(context.firstKey(), context.absentValue());
        result.set(value);
      });
      await().untilTrue(started);
      var threadState = EnumSet.of(BLOCKED, WAITING);
      await().until(() -> threadState.contains(thread.get().getState()));
      node.setVariableTime(context.ticker().read() + currentDuration);
    }

    var expected = context.original().get(context.firstKey());
    await().untilAtomic(result, is(expected));
    assertThat(node.getVariableTime()).isEqualTo(
        context.ticker().read() + context.expiryTime().timeNanos());
    verify(context.expiry()).expireAfterRead(context.firstKey(),
        context.absentValue(), context.ticker().read(), currentDuration);
    verifyNoMoreInteractions(context.expiry());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      scheduler = CacheScheduler.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void unschedule_cleanUp(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var future = Mockito.mock(Future.class);
    doReturn(future).when(context.scheduler()).schedule(any(), any(), anyLong(), any());

    for (int i = 0; i < 10; i++) {
      cache.put(Int.valueOf(i), Int.valueOf(-i));
    }
    assertThat(cache.pacer().nextFireTime).isNotEqualTo(0);
    assertThat(cache.pacer().future).isNotNull();

    context.ticker().advance(1, TimeUnit.HOURS);
    cache.cleanUp();

    verify(future).cancel(false);
    assertThat(cache.pacer().nextFireTime).isEqualTo(0);
    assertThat(cache.pacer().future).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(compute = Compute.SYNC, population = Population.EMPTY,
      scheduler = CacheScheduler.MOCKITO, expiryTime = Expire.ONE_MINUTE,
      mustExpireWithAnyOf = { AFTER_ACCESS, AFTER_WRITE, VARIABLE },
      expiry = { CacheExpiry.DISABLED, CacheExpiry.CREATE, CacheExpiry.WRITE, CacheExpiry.ACCESS },
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void unschedule_invalidateAll(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var future = Mockito.mock(Future.class);
    doReturn(future).when(context.scheduler()).schedule(any(), any(), anyLong(), any());

    for (int i = 0; i < 10; i++) {
      cache.put(Int.valueOf(i), Int.valueOf(-i));
    }
    assertThat(cache.pacer().nextFireTime).isNotEqualTo(0);
    assertThat(cache.pacer().future).isNotNull();

    cache.clear();
    verify(future).cancel(false);
    assertThat(cache.pacer().nextFireTime).isEqualTo(0);
    assertThat(cache.pacer().future).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expireAfterAccess = Expire.ONE_MINUTE,
      maximumSize = {Maximum.DISABLED, Maximum.FULL}, weigher = CacheWeigher.DISABLED)
  public void expirationDelay_window(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    int maximum = cache.evicts() ? (int) context.maximumSize() : 100;
    long stepSize = context.expireAfterAccess().timeNanos() / (2 * maximum);
    for (int i = 0; i < maximum; i++) {
      var key = CacheContext.intern(Int.valueOf(i));
      cache.put(key, key);
      context.ticker().advance(stepSize, TimeUnit.NANOSECONDS);
    }

    if (cache.evicts()) {
      for (var node : List.copyOf(cache.accessOrderProbationDeque())) {
        node.setAccessTime(context.ticker().read());
      }
      for (var node : List.copyOf(cache.accessOrderProtectedDeque())) {
        node.setAccessTime(context.ticker().read());
      }
      for (var node : FluentIterable.from(cache.accessOrderProbationDeque()).skip(5).toList()) {
        cache.get(node.getKey());
      }
      context.ticker().advance(stepSize, TimeUnit.NANOSECONDS);
      cache.cleanUp();
    }

    var expectedDelay = context.expireAfterAccess().timeNanos()
        - (context.ticker().read() - cache.accessOrderWindowDeque().getFirst().getAccessTime());
    assertThat(cache.getExpirationDelay(context.ticker().read())).isEqualTo(expectedDelay);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expireAfterAccess = Expire.ONE_MINUTE,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED)
  public void expirationDelay_probation(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    long stepSize = context.expireAfterAccess().timeNanos() / (2 * context.maximumSize());
    for (int i = 0; i < (int) context.maximumSize(); i++) {
      var key = CacheContext.intern(Int.valueOf(i));
      cache.put(key, key);
      context.ticker().advance(stepSize, TimeUnit.NANOSECONDS);
    }

    for (var node : List.copyOf(cache.accessOrderWindowDeque())) {
      node.setAccessTime(context.ticker().read());
    }
    for (var node : List.copyOf(cache.accessOrderProtectedDeque())) {
      node.setAccessTime(context.ticker().read());
    }
    for (var node : FluentIterable.from(cache.accessOrderProbationDeque()).skip(5).toList()) {
      cache.get(node.getKey());
    }
    context.ticker().advance(stepSize, TimeUnit.NANOSECONDS);
    cache.cleanUp();

    var expectedDelay = context.expireAfterAccess().timeNanos()
        - (context.ticker().read() - cache.accessOrderProbationDeque().getFirst().getAccessTime());
    assertThat(cache.getExpirationDelay(context.ticker().read())).isEqualTo(expectedDelay);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expireAfterAccess = Expire.ONE_MINUTE,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED)
  public void expirationDelay_protected(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    long stepSize = context.expireAfterAccess().timeNanos() / (2 * context.maximumSize());
    for (int i = 0; i < (int) context.maximumSize(); i++) {
      var key = CacheContext.intern(Int.valueOf(i));
      cache.put(key, key);
      context.ticker().advance(stepSize, TimeUnit.NANOSECONDS);
    }

    for (var node : FluentIterable.from(cache.accessOrderProbationDeque()).skip(5).toList()) {
      cache.get(node.getKey());
    }
    context.ticker().advance(stepSize, TimeUnit.NANOSECONDS);
    cache.cleanUp();

    for (var node : List.copyOf(cache.accessOrderWindowDeque())) {
      node.setAccessTime(context.ticker().read());
    }
    for (var node : List.copyOf(cache.accessOrderProbationDeque())) {
      node.setAccessTime(context.ticker().read());
    }

    var expectedDelay = context.expireAfterAccess().timeNanos()
        - (context.ticker().read() - cache.accessOrderProtectedDeque().getFirst().getAccessTime());
    assertThat(cache.getExpirationDelay(context.ticker().read())).isEqualTo(expectedDelay);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, expireAfterWrite = Expire.ONE_MINUTE,
      maximumSize = Maximum.FULL, weigher = CacheWeigher.DISABLED)
  public void expirationDelay_writeOrder(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    long stepSize = context.expireAfterWrite().timeNanos() / (2 * context.maximumSize());
    for (int i = 0; i < (int) context.maximumSize(); i++) {
      var key = CacheContext.intern(Int.valueOf(i));
      cache.put(key, key);
      context.ticker().advance(stepSize, TimeUnit.NANOSECONDS);
    }
    for (var key : cache.keySet()) {
      cache.get(key);
    }
    cache.cleanUp();

    var expectedDelay = context.expireAfterWrite().timeNanos()
        - (context.ticker().read() - cache.writeOrderDeque().getFirst().getWriteTime());
    assertThat(cache.getExpirationDelay(context.ticker().read())).isEqualTo(expectedDelay);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, maximumSize = {Maximum.DISABLED, Maximum.FULL},
      expiry = CacheExpiry.WRITE, expiryTime = Expire.ONE_MINUTE)
  public void expirationDelay_varTime(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    long startTime = context.ticker().read();
    int maximum = cache.evicts() ? (int) context.maximumSize() : 100;
    long stepSize = context.expiryTime().timeNanos() / (2 * maximum);
    for (int i = 0; i < maximum; i++) {
      var key = CacheContext.intern(Int.valueOf(i));
      cache.put(key, key);
      context.ticker().advance(stepSize, TimeUnit.NANOSECONDS);
    }
    for (var key : cache.keySet()) {
      cache.get(key);
    }
    cache.cleanUp();

    var expectedDelay = context.expiryTime().timeNanos() - (context.ticker().read() - startTime);
    assertThat(cache.getExpirationDelay(context.ticker().read())).isIn(
        Range.closed(expectedDelay - TimerWheel.SPANS[0], expectedDelay));
  }

  /* --------------- Refresh --------------- */

  @Test(dataProvider = "caches") // Issue #715
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED,
      compute = Compute.SYNC, stats = Stats.DISABLED)
  public void refreshIfNeeded_liveliness(CacheContext context) {
    var stats = Mockito.mock(StatsCounter.class);
    context.caffeine().recordStats(() -> stats);

    // Capture the refresh parameters, should not be retired/dead sentinel entry
    var refreshEntry = new AtomicReference<Map.Entry<Object, Object>>();
    var cache = asBoundedLocalCache(context.build(new CacheLoader<Object, Object>() {
      @Override public Int load(Object key) {
        throw new AssertionError();
      }
      @Override public CompletableFuture<Object> asyncReload(
          Object key, Object oldValue, Executor executor) {
        refreshEntry.set(new SimpleEntry<>(key, oldValue));
        return CompletableFuture.completedFuture(key);
      }
    }));

    // Seed the cache
    cache.put(context.absentKey(), context.absentValue());
    var node = cache.data.get(context.absentKey());

    // Remove the entry after the read, but before the refresh, and leave it as retired
    doAnswer(invocation -> {
      ConcurrentTestHarness.execute(() -> {
        cache.remove(context.absentKey());
      });
      await().until(() -> !cache.containsKey(context.absentKey()));
      assertThat(node.isRetired()).isTrue();
      return null;
    }).when(stats).recordHits(1);

    // Ensure that the refresh operation was skipped
    cache.evictionLock.lock();
    try {
      context.ticker().advance(10, TimeUnit.MINUTES);
      var value = cache.getIfPresent(context.absentKey(), /* recordStats */ true);
      assertThat(value).isEqualTo(context.absentValue());
      assertThat(refreshEntry.get()).isNull();
    } finally {
      cache.evictionLock.unlock();
    }
  }

  @Test(dataProvider = "caches", groups = "isolated")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      refreshAfterWrite = Expire.ONE_MINUTE, executor = CacheExecutor.THREADED,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      expiry = CacheExpiry.DISABLED, maximumSize = Maximum.DISABLED, compute = Compute.SYNC,
      removalListener = Listener.DISABLED, evictionListener = Listener.DISABLED,
      stats = Stats.DISABLED)
  public void refreshIfNeeded_softLock(CacheContext context) {
    var refresh = new AtomicBoolean();
    var reloading = new AtomicBoolean();
    var future = new CompletableFuture<Int>();
    var newValue = context.absentValue().add(1);
    var cache = context.build(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        throw new IllegalStateException();
      }
      @Override
      public CompletableFuture<Int> asyncReload(Int key, Int oldValue, Executor executor) {
        reloading.set(true);
        await().untilTrue(refresh);
        return future;
      }
    });
    var localCache = asBoundedLocalCache(cache);
    cache.put(context.absentKey(), context.absentValue());
    var lookupKey = localCache.nodeFactory.newLookupKey(context.absentKey());
    var node = localCache.data.get(lookupKey);
    var refreshes = localCache.refreshes();

    context.ticker().advance(2, TimeUnit.MINUTES);
    ConcurrentTestHarness.execute(() -> cache.get(context.absentKey()));

    await().untilTrue(reloading);
    assertThat(node.getWriteTime() & 1L).isEqualTo(1);

    localCache.refreshes = null;
    cache.get(context.absentKey());
    assertThat(localCache.refreshes).isNull();

    refresh.set(true);
    await().untilAsserted(() -> assertThat(refreshes).isNotEmpty());
    await().untilAsserted(() -> assertThat(node.getWriteTime() & 1L).isEqualTo(0));

    future.complete(newValue);
    await().untilAsserted(() -> assertThat(refreshes).isEmpty());
    await().untilAsserted(() -> assertThat(cache).containsEntry(context.absentKey(), newValue));
  }

  @Test(dataProvider = "caches", groups = "isolated")
  @CacheSpec(population = Population.EMPTY, executor = CacheExecutor.THREADED,
      compute = Compute.ASYNC, stats = Stats.DISABLED)
  public void refresh_startReloadBeforeLoadCompletion(CacheContext context) {
    var stats = Mockito.mock(StatsCounter.class);
    var beganLoadSuccess = new AtomicBoolean();
    var endLoadSuccess = new CountDownLatch(1);
    var beganReloading = new AtomicBoolean();
    var beganLoading = new AtomicBoolean();
    var endReloading = new AtomicBoolean();
    var endLoading = new AtomicBoolean();

    context.ticker().setAutoIncrementStep(Duration.ofSeconds(1));
    context.caffeine().recordStats(() -> stats);
    var asyncCache = context.buildAsync(new CacheLoader<Int, Int>() {
      @Override public Int load(Int key) {
        beganLoading.set(true);
        await().untilTrue(endLoading);
        return new Int(ThreadLocalRandom.current().nextInt());
      }
      @Override public Int reload(Int key, Int oldValue) {
        beganReloading.set(true);
        await().untilTrue(endReloading);
        return new Int(ThreadLocalRandom.current().nextInt());
      }
    });

    Answer<?> answer = invocation -> {
      beganLoadSuccess.set(true);
      endLoadSuccess.await();
      return null;
    };
    doAnswer(answer).when(stats).recordLoadSuccess(anyLong());

    // Start load
    var future1 = asyncCache.get(context.absentKey());
    await().untilTrue(beganLoading);

    // Complete load; start load callback
    endLoading.set(true);
    await().untilTrue(beganLoadSuccess);

    // Start reload
    var refresh = asyncCache.synchronous().refresh(context.absentKey());
    await().untilTrue(beganReloading);

    // Complete load callback
    endLoadSuccess.countDown();
    await().untilAsserted(() -> assertThat(future1.getNumberOfDependents()).isEqualTo(0));

    // Complete reload callback
    endReloading.set(true);
    await().untilAsserted(() -> assertThat(refresh.getNumberOfDependents()).isEqualTo(0));

    // Assert new value
    await().untilAsserted(() ->
        assertThat(asyncCache.get(context.absentKey())).succeedsWith(refresh.get()));
  }

  /* --------------- Broken Equality --------------- */

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG,
      maximumSize = Maximum.FULL, weigher = {CacheWeigher.DISABLED, CacheWeigher.MAX_VALUE})
  public void brokenEquality_eviction(BoundedLocalCache<Object, Int> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    var key = new MutableInt(context.absentKey().intValue());
    cache.put(key, context.absentValue());
    key.increment();

    eviction.setMaximum(0);
    assertThat(Map.copyOf(cache)).isEmpty();
    assertThat(context).notifications().isEmpty();
    assertThat(cache.estimatedSize()).isEqualTo(1);

    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getMessage()).contains("An invalid state was detected");
    assertThat(event.getLevel()).isEqualTo(ERROR);

    cache.data.clear();
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG,
      mustExpireWithAnyOf = {AFTER_ACCESS, AFTER_WRITE, VARIABLE},
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      expiry = CacheExpiry.CREATE,  expiryTime = Expire.ONE_MINUTE)
  public void brokenEquality_expiration(
      BoundedLocalCache<Object, Int> cache, CacheContext context) {
    var key = new MutableInt(context.absentKey().intValue());
    cache.put(key, context.absentValue());
    key.increment();

    context.ticker().advance(1, TimeUnit.DAYS);
    cache.cleanUp();

    assertThat(Map.copyOf(cache)).isEmpty();
    assertThat(context).notifications().isEmpty();
    assertThat(cache.estimatedSize()).isEqualTo(1);

    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getMessage()).contains("An invalid state was detected");
    assertThat(event.getLevel()).isEqualTo(ERROR);

    cache.data.clear();
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void brokenEquality_clear(BoundedLocalCache<Object, Int> cache, CacheContext context) {
    var key = new MutableInt(context.absentKey().intValue());
    cache.put(key, context.absentValue());
    key.increment();

    cache.clear();
    assertThat(Map.copyOf(cache)).isEmpty();
    assertThat(context).notifications().isEmpty();
    assertThat(cache.estimatedSize()).isEqualTo(1);

    var event = Iterables.getOnlyElement(TestLoggerFactory.getLoggingEvents());
    assertThat(event.getMessage()).contains("An invalid state was detected");
    assertThat(event.getLevel()).isEqualTo(ERROR);

    cache.data.clear();
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void brokenEquality_put(BoundedLocalCache<MutableInt, Int> cache, CacheContext context) {
    testForBrokenEquality(cache, context, key -> cache.put(key, context.absentValue()));
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void brokenEquality_putIfAbsent(
      BoundedLocalCache<MutableInt, Int> cache, CacheContext context) {
    testForBrokenEquality(cache, context, key -> cache.putIfAbsent(key, context.absentValue()));
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void brokenEquality_replace(
      BoundedLocalCache<MutableInt, Int> cache, CacheContext context) {
    testForBrokenEquality(cache, context, key -> cache.replace(key, context.absentValue()));
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void brokenEquality_replaceConditionally(
      BoundedLocalCache<MutableInt, Int> cache, CacheContext context) {
    testForBrokenEquality(cache, context, key ->
        cache.replace(key, context.absentValue(), context.absentValue().negate()));
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void brokenEquality_remove(
      BoundedLocalCache<MutableInt, Int> cache, CacheContext context) {
    testForBrokenEquality(cache, context, cache::remove);
  }

  @CheckMaxLogLevel(ERROR)
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, keys = ReferenceType.STRONG)
  public void brokenEquality_removeConditionally(
      BoundedLocalCache<MutableInt, Int> cache, CacheContext context) {
    testForBrokenEquality(cache, context, key -> cache.remove(key, context.absentValue()));
  }

  private static void testForBrokenEquality(BoundedLocalCache<MutableInt, Int> cache,
      CacheContext context, Consumer<MutableInt> task) {
    var key = new MutableInt(context.absentKey().intValue());
    cache.put(key, context.absentValue());
    key.increment();

    cache.clear();
    key.decrement();

    try {
      task.accept(key);
      Assert.fail();
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("An invalid state was detected");
    } finally {
      cache.data.clear();
    }
  }

  /* --------------- Miscellaneous --------------- */

  @Test
  public void cacheFactory_invalid() {
    try {
      LocalCacheFactory.loadFactory(/* builder */ null, /* loader */ null,
          /* async */ false, /* className */ null);
      Assert.fail();
    } catch (NullPointerException expected) {}
    try {
      LocalCacheFactory.loadFactory(/* builder */ null, /* loader */ null,
          /* async */ false, /* className */ "");
      Assert.fail();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasCauseThat().isInstanceOf(ClassNotFoundException.class);
    }
  }

  @Test
  public void nodeFactory_invalid() {
    try {
      NodeFactory.loadFactory(/* className */ null);
      Assert.fail();
    } catch (NullPointerException expected) {}
    try {
      NodeFactory.loadFactory(/* className */ "");
      Assert.fail();
    } catch (IllegalStateException expected) {
      assertThat(expected).hasCauseThat().isInstanceOf(ClassNotFoundException.class);
    }
  }

  @Test
  public void unsupported() {
    var cache = Mockito.mock(BoundedLocalCache.class, InvocationOnMock::callRealMethod);
    @SuppressWarnings("MethodReferenceUsage")
    List<Runnable> methods = List.of(
        () -> cache.accessOrderWindowDeque(),       () -> cache.accessOrderProbationDeque(),
        () -> cache.accessOrderProtectedDeque(),    () -> cache.writeOrderDeque(),
        () -> cache.expiresAfterAccessNanos(),      () -> cache.setExpiresAfterAccessNanos(1L),
        () -> cache.expiresAfterWriteNanos(),       () -> cache.setExpiresAfterWriteNanos(1L),
        () -> cache.refreshAfterWriteNanos(),       () -> cache.setRefreshAfterWriteNanos(1L),
        () -> cache.timerWheel(),                   () -> cache.frequencySketch(),
        () -> cache.maximum(),                      () -> cache.windowMaximum(),
        () -> cache.mainProtectedMaximum(),         () -> cache.setMaximum(1L),
        () -> cache.setWindowMaximum(1L),           () -> cache.setMainProtectedMaximum(1L),
        () -> cache.weightedSize(),                 () -> cache.windowWeightedSize(),
        () -> cache.mainProtectedWeightedSize(),    () -> cache.setWeightedSize(1L),
        () -> cache.setWindowWeightedSize(0),       () -> cache.setMainProtectedWeightedSize(1L),
        () -> cache.hitsInSample(),                 () -> cache.missesInSample(),
        () -> cache.sampleCount(),                  () -> cache.stepSize(),
        () -> cache.previousSampleHitRate(),        () -> cache.adjustment(),
        () -> cache.setHitsInSample(1),             () -> cache.setMissesInSample(1),
        () -> cache.setSampleCount(1),              () -> cache.setStepSize(1.0),
        () -> cache.setPreviousSampleHitRate(1.0),  () -> cache.setAdjustment(1L));
    for (var method : methods) {
      try {
        method.run();
        Assert.fail();
      } catch (UnsupportedOperationException expected) {}
    }
  }

  @Test
  public void cleanupTask_ignore() {
    var task = new PerformCleanupTask(null);
    assertThat(task.getRawResult()).isNull();
    task.completeExceptionally(null);
    task.setRawResult(null);
    task.complete(null);
    task.cancel(true);
  }

  @Test(dataProviderClass = CacheProvider.class, dataProvider = "caches")
  @CacheSpec(population = Population.SINGLETON, compute = Compute.SYNC,
      initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL})
  public void node_string(BoundedLocalCache<Int, Int> cache, CacheContext context) {
    var node = cache.data.values().iterator().next();
    var description = node.toString();
    assertThat(description).contains("key=" + node.getKey());
    assertThat(description).contains("value=" + node.getValue());
    assertThat(description).contains("weight=" + node.getWeight());
    assertThat(description).contains(String.format(US, "accessTimeNS=%,d", node.getAccessTime()));
    assertThat(description).contains(String.format(US, "writeTimeNS=%,d", node.getWriteTime()));
    assertThat(description).contains(String.format(US, "varTimeNs=%,d", node.getVariableTime()));
  }

  @Test
  public void node_unsupported() {
    @SuppressWarnings("unchecked")
    Node<Object, Object> node = Mockito.mock(Node.class, InvocationOnMock::callRealMethod);
    @SuppressWarnings("MethodReferenceUsage")
    List<Runnable> methods = List.of(
        () -> node.getPreviousInVariableOrder(),    () -> node.getNextInVariableOrder(),
        () -> node.setPreviousInVariableOrder(node),() -> node.setNextInVariableOrder(node),
        () -> node.setPreviousInAccessOrder(node),  () -> node.setNextInAccessOrder(node),
        () -> node.setPreviousInWriteOrder(node),   () -> node.setNextInWriteOrder(node),
        () -> node.casVariableTime(1L, 2L),         () -> node.casWriteTime(1L, 2L),
        () -> node.setQueueType(WINDOW));
    for (var method : methods) {
      try {
        method.run();
        Assert.fail();
      } catch (UnsupportedOperationException expected) {}
    }
  }

  @Test
  public void node_ignored() {
    var node = Mockito.mock(Node.class, InvocationOnMock::callRealMethod);
    List<Runnable> methods = List.of(() -> node.setVariableTime(1L),
        () -> node.setAccessTime(1L), () -> node.setWriteTime(1L));
    for (var method : methods) {
      method.run();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void policy_unsupported() {
    Policy<Object, Object> policy = Mockito.mock(Policy.class, InvocationOnMock::callRealMethod);
    var eviction = Mockito.mock(Eviction.class, InvocationOnMock::callRealMethod);
    var fixedExpiration = Mockito.mock(FixedExpiration.class, InvocationOnMock::callRealMethod);
    var varExpiration = Mockito.mock(VarExpiration.class, InvocationOnMock::callRealMethod);
    List<Runnable> methods = List.of(() -> policy.getEntryIfPresentQuietly(new Object()),
        () -> eviction.coldestWeighted(1L), () -> eviction.coldest(identity()),
        () -> eviction.hottestWeighted(1L), () -> eviction.hottest(identity()),
        () -> fixedExpiration.oldest(identity()), () -> fixedExpiration.youngest(identity()),
        () -> varExpiration.compute(new Object(), (k, v) -> v, Duration.ZERO),
        () -> varExpiration.oldest(identity()), () -> varExpiration.youngest(identity()));
    for (var method : methods) {
      try {
        method.run();
        Assert.fail();
      } catch (UnsupportedOperationException expected) {}
    }
  }

  @Test
  public void fixedExpireAfterWrite() {
    int key = 1;
    int value = 2;
    long duration = TimeUnit.DAYS.toNanos(1);
    long currentTime = ThreadLocalRandom.current().nextLong();
    long currentDuration = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);

    var expiry = new FixedExpireAfterWrite<>(1, TimeUnit.DAYS);
    assertThat(expiry.expireAfterCreate(key, value, currentTime)).isEqualTo(duration);
    assertThat(expiry.expireAfterUpdate(
        key, value, currentTime, currentDuration)).isEqualTo(duration);
    assertThat(expiry.expireAfterRead(
        key, value, currentTime, currentDuration)).isEqualTo(currentDuration);
  }

  static <K, V> BoundedLocalCache<K, V> asBoundedLocalCache(Cache<K, V> cache) {
    return (BoundedLocalCache<K, V>) cache.asMap();
  }
}
