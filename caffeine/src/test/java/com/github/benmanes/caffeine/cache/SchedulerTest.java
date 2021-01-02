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
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.scheduledExecutor;
import static com.google.common.util.concurrent.testing.TestingExecutors.sameThreadScheduledExecutor;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.LogManager;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.NullPointerTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("FutureReturnValueIgnored")
public final class SchedulerTest {
  static {
    // disable logging warnings caused by exceptions
    LogManager.getLogManager().reset();
  }

  private final NullPointerTester npeTester = new NullPointerTester();

  @Test(dataProvider = "schedulers")
  public void scheduler_null(Scheduler scheduler) {
    npeTester.testAllPublicInstanceMethods(scheduler);
  }

  @Test(dataProvider = "runnableSchedulers")
  public void scheduler_exception(Scheduler scheduler) {
    AtomicBoolean executed = new AtomicBoolean();
    Executor executor = task -> {
      executed.set(true);
      throw new IllegalStateException();
    };
    scheduler.schedule(executor, () -> {}, 1L, TimeUnit.NANOSECONDS);
    await().untilTrue(executed);
  }

  @Test(dataProvider = "runnableSchedulers")
  public void scheduler(Scheduler scheduler) {
    AtomicBoolean executed = new AtomicBoolean();
    Runnable task = () -> executed.set(true);
    scheduler.schedule(ConcurrentTestHarness.executor, task, 1L, TimeUnit.NANOSECONDS);
    await().untilTrue(executed);
  }

  /* --------------- disabled --------------- */

  @Test
  public void disabledScheduler() {
    Future<?> future = Scheduler.disabledScheduler()
        .schedule(Runnable::run, () -> {}, 1, TimeUnit.MINUTES);
    assertThat(future, is(DisabledFuture.INSTANCE));
  }

  @Test
  public void disabledFuture_null() {
    npeTester.testAllPublicInstanceMethods(DisabledFuture.INSTANCE);
  }

  /* --------------- guarded --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void guardedScheduler_null() {
    Scheduler.guardedScheduler(null);
  }

  @Test
  public void guardedScheduler_nullFuture() {
    ScheduledExecutorService scheduledExecutor = Mockito.mock(ScheduledExecutorService.class);
    Scheduler scheduler = Scheduler.forScheduledExecutorService(scheduledExecutor);
    Executor executor = Mockito.mock(Executor.class);
    Runnable command = () -> {};

    Future<?> future = Scheduler.guardedScheduler(scheduler)
        .schedule(executor, command, 1L, TimeUnit.MINUTES);
    verify(scheduledExecutor).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.MINUTES));
    assertThat(future, is(DisabledFuture.INSTANCE));
  }

  @Test
  public void guardedScheduler() {
    Future<?> future = Scheduler.guardedScheduler(Scheduler.disabledScheduler())
        .schedule(Runnable::run, () -> {}, 1, TimeUnit.MINUTES);
    assertThat(future, is(DisabledFuture.INSTANCE));
  }

  /* --------------- ScheduledExecutorService --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void scheduledExecutorService_null() {
    Scheduler.forScheduledExecutorService(null);
  }

  @Test
  public void scheduledExecutorService_schedule() {
    ScheduledExecutorService scheduledExecutor = Mockito.mock(ScheduledExecutorService.class);
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    Executor executor = Mockito.mock(Executor.class);
    Runnable command = () -> {};

    Scheduler scheduler = Scheduler.forScheduledExecutorService(scheduledExecutor);
    Future<?> future = scheduler.schedule(executor, command, 1L, TimeUnit.MINUTES);
    assertThat(future, is(not(DisabledFuture.INSTANCE)));

    verify(scheduledExecutor).isShutdown();
    verify(scheduledExecutor).schedule(task.capture(), eq(1L), eq(TimeUnit.MINUTES));
    verifyNoMoreInteractions(scheduledExecutor);

    task.getValue().run();
    verify(executor).execute(command);
    verifyNoMoreInteractions(executor);
  }

  @Test
  public void scheduledExecutorService_shutdown() {
    ScheduledExecutorService scheduledExecutor = Mockito.mock(ScheduledExecutorService.class);
    Executor executor = Mockito.mock(Executor.class);

    when(scheduledExecutor.isShutdown()).thenReturn(true);
    Scheduler scheduler = Scheduler.forScheduledExecutorService(scheduledExecutor);
    Future<?> future = scheduler.schedule(executor, () -> {}, 1L, TimeUnit.MINUTES);
    assertThat(future, is(DisabledFuture.INSTANCE));

    verify(scheduledExecutor).isShutdown();
    verifyNoMoreInteractions(scheduledExecutor);
    verifyNoInteractions(executor);
  }

  /* --------------- providers --------------- */

  @DataProvider(name = "schedulers")
  public Iterator<Scheduler> providesSchedulers() {
    ImmutableSet<Scheduler> schedulers = ImmutableSet.of(
        Scheduler.forScheduledExecutorService(sameThreadScheduledExecutor()),
        Scheduler.forScheduledExecutorService(scheduledExecutor),
        Scheduler.disabledScheduler(),
        Scheduler.systemScheduler());
    return schedulers.iterator();
  }

  @DataProvider(name = "runnableSchedulers")
  public Iterator<Scheduler> providesRunnableSchedulers() {
    ImmutableSet<Scheduler> schedulers = ImmutableSet.of(
        Scheduler.forScheduledExecutorService(sameThreadScheduledExecutor()),
        Scheduler.forScheduledExecutorService(scheduledExecutor),
        Scheduler.systemScheduler());
    return schedulers.stream()
        .filter(scheduler -> scheduler != Scheduler.disabledScheduler())
        .iterator();
  }
}
