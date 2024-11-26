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

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A scheduler that submits a task to an executor after a given delay.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NullMarked
@FunctionalInterface
public interface Scheduler {

  /**
   * Returns a future that will submit the task to the executor after the given delay.
   *
   * @param executor the executor to run the task
   * @param command the runnable task to schedule
   * @param delay how long to delay, in units of {@code unit}
   * @param unit a {@code TimeUnit} determining how to interpret the {@code delay} parameter
   * @return a scheduled future representing the pending submission of the task
   */
  Future<? extends @Nullable Object> schedule(
      Executor executor, Runnable command, long delay, TimeUnit unit);

  /**
   * Returns a scheduler that always returns a successfully completed future.
   *
   * @return a scheduler that always returns a successfully completed future
   */
  static Scheduler disabledScheduler() {
    return DisabledScheduler.INSTANCE;
  }

  /**
   * Returns a scheduler that uses the system-wide scheduling thread by using
   * {@link CompletableFuture#delayedExecutor}.
   *
   * @return a scheduler that uses the system-wide scheduling thread
   */
  static Scheduler systemScheduler() {
    return SystemScheduler.INSTANCE;
  }

  /**
   * Returns a scheduler that delegates to the a {@link ScheduledExecutorService}.
   * <p>
   * Note that this implementation will ignore scheduling the task if the executor was shutdown or
   * the submission was rejected. Consider implementing your own adapter if different behavior is
   * required.
   *
   * @param scheduledExecutorService the executor to schedule on
   * @return a scheduler that delegates to the a {@link ScheduledExecutorService}
   */
  static Scheduler forScheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
    return new ExecutorServiceScheduler(scheduledExecutorService);
  }

  /**
   * Returns a scheduler that suppresses and logs any exception thrown by the delegate
   * {@code scheduler}.
   *
   * @param scheduler the scheduler to delegate to
   * @return a scheduler that suppresses and logs any exception thrown by the delegate
   */
  static Scheduler guardedScheduler(Scheduler scheduler) {
    return (scheduler instanceof GuardedScheduler) ? scheduler : new GuardedScheduler(scheduler);
  }
}

enum SystemScheduler implements Scheduler {
  INSTANCE;

  @Override
  public Future<?> schedule(Executor executor, Runnable command, long delay, TimeUnit unit) {
    Executor delayedExecutor = CompletableFuture.delayedExecutor(delay, unit, executor);
    return CompletableFuture.runAsync(command, delayedExecutor);
  }
}

final class ExecutorServiceScheduler implements Scheduler, Serializable {
  private static final Logger logger = System.getLogger(ExecutorServiceScheduler.class.getName());
  private static final long serialVersionUID = 1;

  @SuppressWarnings("serial")
  final ScheduledExecutorService scheduledExecutorService;

  ExecutorServiceScheduler(ScheduledExecutorService scheduledExecutorService) {
    this.scheduledExecutorService = requireNonNull(scheduledExecutorService);
  }

  @Override
  public Future<? extends @Nullable Object> schedule(
      Executor executor, Runnable command, long delay, TimeUnit unit) {
    requireNonNull(executor);
    requireNonNull(command);
    requireNonNull(unit);

    if (scheduledExecutorService.isShutdown()) {
      return DisabledFuture.instance();
    }
    return scheduledExecutorService.schedule(() -> {
      try {
        executor.execute(command);
      } catch (Throwable t) {
        logger.log(Level.WARNING, "Exception thrown when submitting scheduled task", t);
        throw t;
      }
    }, delay, unit);
  }
}

final class GuardedScheduler implements Scheduler, Serializable {
  private static final Logger logger = System.getLogger(GuardedScheduler.class.getName());
  private static final long serialVersionUID = 1;

  @SuppressWarnings("serial")
  final Scheduler delegate;

  GuardedScheduler(Scheduler delegate) {
    this.delegate = requireNonNull(delegate);
  }

  @Override
  public Future<? extends @Nullable Object> schedule(
      Executor executor, Runnable command, long delay, TimeUnit unit) {
    try {
      var future = delegate.schedule(executor, command, delay, unit);
      return (future == null) ? DisabledFuture.instance() : future;
    } catch (Throwable t) {
      logger.log(Level.WARNING, "Exception thrown by scheduler; discarded task", t);
      return DisabledFuture.instance();
    }
  }
}

enum DisabledScheduler implements Scheduler {
  INSTANCE;

  @Override
  public Future<? extends @Nullable Object> schedule(
      Executor executor, Runnable command, long delay, TimeUnit unit) {
    requireNonNull(executor);
    requireNonNull(command);
    requireNonNull(unit);
    return DisabledFuture.instance();
  }
}

@SuppressWarnings("CheckedExceptionNotThrown")
enum DisabledFuture implements Future<@Nullable Void> {
  INSTANCE;

  @SuppressWarnings("NullAway")
  static Future<? extends @Nullable Object> instance() {
    return INSTANCE;
  }

  @Override public boolean isDone() {
    return true;
  }
  @Override public boolean isCancelled() {
    return false;
  }
  @Override public boolean cancel(boolean mayInterruptIfRunning) {
    return false;
  }
  @Override public @Nullable Void get(long timeout, TimeUnit unit) {
    requireNonNull(unit);
    return null;
  }
  @Override public @Nullable Void get() {
    return null;
  }
}
