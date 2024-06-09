/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.executor;
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.scheduledExecutor;
import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.testing.TestingExecutors.sameThreadScheduledExecutor;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.WARN;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.google.common.testing.NullPointerTester;
import com.google.common.util.concurrent.Futures;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class SchedulerTest {
  private final NullPointerTester npeTester = new NullPointerTester();

  @BeforeMethod @AfterMethod
  public void reset() {
    TestLoggerFactory.clear();
  }

  @Test(dataProvider = "schedulers")
  public void scheduler_null(Scheduler scheduler) {
    npeTester.testAllPublicInstanceMethods(scheduler);
  }

  @Test(dataProvider = "runnableSchedulers")
  public void scheduler_exception(Scheduler scheduler) {
    var thread = new AtomicReference<Thread>();
    Executor executor = task -> {
      thread.set(Thread.currentThread());
      throw new IllegalStateException();
    };
    var future = scheduler.schedule(executor, () -> {}, 1L, TimeUnit.NANOSECONDS);
    assertThat(future).isNotNull();
    await().untilAtomic(thread, is(not(nullValue())));

    if (thread.get() == Thread.currentThread()) {
      assertThat(logEvents()
          .withMessage("Exception thrown when submitting scheduled task")
          .withThrowable(IllegalStateException.class)
          .withLevel(WARN)
          .exclusively())
          .hasSize(1);
    }
  }

  @Test(dataProvider = "runnableSchedulers")
  public void scheduler(Scheduler scheduler) {
    var executed = new AtomicBoolean();
    Runnable task = () -> executed.set(true);
    var future = scheduler.schedule(executor, task, 1L, TimeUnit.NANOSECONDS);
    assertThat(future).isNotNull();
    await().untilTrue(executed);
  }

  /* --------------- disabled --------------- */

  @Test
  public void disabledScheduler() {
    var future = Scheduler.disabledScheduler()
        .schedule(Runnable::run, () -> {}, 1, TimeUnit.MINUTES);
    assertThat(future).isSameInstanceAs(DisabledFuture.INSTANCE);
  }

  @Test
  public void disabledFuture() {
    assertThat(DisabledFuture.INSTANCE.get(0, TimeUnit.SECONDS)).isNull();
    assertThat(DisabledFuture.INSTANCE.isCancelled()).isFalse();
    assertThat(DisabledFuture.INSTANCE.cancel(false)).isFalse();
    assertThat(DisabledFuture.INSTANCE.cancel(true)).isFalse();
    assertThat(DisabledFuture.INSTANCE.isDone()).isTrue();
    assertThat(DisabledFuture.INSTANCE.get()).isNull();
  }

  @Test
  public void disabledFuture_null() {
    npeTester.testAllPublicInstanceMethods(DisabledFuture.INSTANCE);
  }

  /* --------------- guarded --------------- */

  public void guardedScheduler_null() {
    assertThrows(NullPointerException.class, () -> Scheduler.guardedScheduler(null));
  }

  @Test
  public void guardedScheduler_nullFuture() {
    ScheduledExecutorService scheduledExecutor = Mockito.mock();
    var scheduler = Scheduler.forScheduledExecutorService(scheduledExecutor);
    Executor executor = Mockito.mock();
    Runnable command = () -> {};

    var future = Scheduler.guardedScheduler(scheduler)
        .schedule(executor, command, 1L, TimeUnit.MINUTES);
    verify(scheduledExecutor).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.MINUTES));
    assertThat(future).isSameInstanceAs(DisabledFuture.INSTANCE);
  }

  @Test
  public void guardedScheduler() {
    var future = Scheduler.guardedScheduler((r, e, d, u) -> Futures.immediateVoidFuture())
        .schedule(Runnable::run, () -> {}, 1, TimeUnit.MINUTES);
    assertThat(future).isSameInstanceAs(Futures.immediateVoidFuture());
  }

  @Test
  public void guardedScheduler_exception() {
    var future = Scheduler.guardedScheduler((r, e, d, u) -> { throw new IllegalStateException(); })
        .schedule(Runnable::run, () -> {}, 1, TimeUnit.MINUTES);
    assertThat(future).isSameInstanceAs(DisabledFuture.INSTANCE);
    assertThat(logEvents()
        .withMessage("Exception thrown by scheduler; discarded task")
        .withThrowable(IllegalStateException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  /* --------------- ScheduledExecutorService --------------- */

  @Test
  public void scheduledExecutorService_null() {
    assertThrows(NullPointerException.class, () -> Scheduler.forScheduledExecutorService(null));
  }

  @Test
  public void scheduledExecutorService_schedule() {
    ScheduledExecutorService scheduledExecutor = Mockito.mock();
    var task = ArgumentCaptor.forClass(Runnable.class);
    Executor executor = Mockito.mock();
    Runnable command = () -> {};

    var scheduler = Scheduler.forScheduledExecutorService(scheduledExecutor);
    var future = scheduler.schedule(executor, command, 1L, TimeUnit.MINUTES);
    assertThat(future).isNotSameInstanceAs(DisabledFuture.INSTANCE);

    verify(scheduledExecutor).isShutdown();
    verify(scheduledExecutor).schedule(task.capture(), eq(1L), eq(TimeUnit.MINUTES));
    verifyNoMoreInteractions(scheduledExecutor);

    task.getValue().run();
    verify(executor).execute(command);
    verifyNoMoreInteractions(executor);
  }

  @Test
  public void scheduledExecutorService_shutdown() {
    ScheduledExecutorService scheduledExecutor = Mockito.mock();
    Executor executor = Mockito.mock();

    when(scheduledExecutor.isShutdown()).thenReturn(true);
    var scheduler = Scheduler.forScheduledExecutorService(scheduledExecutor);
    var future = scheduler.schedule(executor, () -> {}, 1L, TimeUnit.MINUTES);
    assertThat(future).isSameInstanceAs(DisabledFuture.INSTANCE);

    verify(scheduledExecutor).isShutdown();
    verifyNoMoreInteractions(scheduledExecutor);
    verifyNoInteractions(executor);
  }

  /* --------------- providers --------------- */

  @DataProvider(name = "schedulers")
  public Iterator<Scheduler> providesSchedulers() {
    var schedulers = Set.of(
        Scheduler.forScheduledExecutorService(sameThreadScheduledExecutor()),
        Scheduler.forScheduledExecutorService(scheduledExecutor),
        Scheduler.disabledScheduler(),
        Scheduler.systemScheduler());
    return schedulers.iterator();
  }

  @DataProvider(name = "runnableSchedulers")
  public Iterator<Scheduler> providesRunnableSchedulers() {
    var schedulers = Set.of(
        Scheduler.forScheduledExecutorService(sameThreadScheduledExecutor()),
        Scheduler.forScheduledExecutorService(scheduledExecutor),
        Scheduler.systemScheduler());
    return schedulers.stream()
        .filter(scheduler -> scheduler != Scheduler.disabledScheduler())
        .iterator();
  }
}
