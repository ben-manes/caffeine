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

import static com.github.benmanes.caffeine.cache.Caffeine.ceilingPowerOfTwo;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * A pacing scheduler that prevents executions from happening too frequently. Only one task may be
 * scheduled at any given time, the earliest pending task takes precedence, and the delay may be
 * increased if it is less than a tolerance threshold.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class Pacer {
  static final long TOLERANCE = ceilingPowerOfTwo(TimeUnit.SECONDS.toNanos(1)); // 1.07s

  final Scheduler scheduler;

  long nextFireTime;
  @Nullable Future<?> future;

  Pacer(Scheduler scheduler) {
    this.scheduler = requireNonNull(scheduler);
  }

  /** Schedules the task, pacing the execution if occurring too often. */
  public void schedule(Executor executor, Runnable command, long now, long delay) {
    long scheduleAt = (now + delay);

    if (future == null) {
      // short-circuit an immediate scheduler causing an infinite loop during initialization
      if (nextFireTime != 0L) {
        return;
      }
    } else if ((nextFireTime - now) > 0L) {
      // Determine whether to reschedule
      if (maySkip(scheduleAt)) {
        return;
      }
      future.cancel(/* mayInterruptIfRunning= */ false);
    }
    long actualDelay = calculateSchedule(now, delay, scheduleAt);
    future = scheduler.schedule(executor, command, actualDelay, TimeUnit.NANOSECONDS);
  }

  /** Attempts to cancel execution of the scheduled task, if present. */
  public void cancel() {
    if (future != null) {
      future.cancel(/* mayInterruptIfRunning= */ false);
      nextFireTime = 0L;
      future = null;
    }
  }

  /** Returns if a task is scheduled to run. */
  public boolean isScheduled() {
    return (future != null) && !future.isDone();
  }

  /**
   * Returns if the current fire time is sooner, or if it is later and within the tolerance limit.
   */
  boolean maySkip(long scheduleAt) {
    long delta = (scheduleAt - nextFireTime);
    return (delta >= 0L) || (-delta <= TOLERANCE);
  }

  /** Returns the delay and sets the next fire time. */
  long calculateSchedule(long now, long delay, long scheduleAt) {
    if (delay <= TOLERANCE) {
      // Use a minimum delay if close to now
      nextFireTime = (now + TOLERANCE);
      return TOLERANCE;
    }
    nextFireTime = scheduleAt;
    return delay;
  }
}
