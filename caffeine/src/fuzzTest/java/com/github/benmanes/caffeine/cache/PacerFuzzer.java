/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertWithMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * A fuzzer that exercises the {@link Pacer} by generating random sequences of schedule, cancel,
 * and future-completion operations. The pacer's state is validated after each operation to ensure
 * that the minimum delay invariant holds and the internal state remains consistent.
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
final class PacerFuzzer {

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  private PacerFuzzer() {}

  @Nested
  static final class ScheduleTest {
    private static final Operation[] OPERATIONS = Operation.values();

    @FuzzTest(maxDuration = "5m")
    void pacer(FuzzedDataProvider data) {
      Executor executor = Runnable::run;
      var state = new SchedulerState();
      var pacer = new Pacer(state);
      Runnable command = () -> {};

      int operations = data.consumeInt(0, 100);
      for (int i = 0; i < operations; i++) {
        var operation = OPERATIONS[data.consumeInt(0, OPERATIONS.length - 1)];
        switch (operation) {
          case SCHEDULE: {
            schedule(data, pacer, state, executor, command);
            break;
          }
          case CANCEL: {
            cancel(pacer);
            break;
          }
          case COMPLETE_FUTURE: {
            completeFuture(pacer);
            break;
          }
        }
      }
    }

    /** Schedules a task and validates the pacer's post-conditions. */
    private static void schedule(FuzzedDataProvider data, Pacer pacer,
        SchedulerState state, Executor executor, Runnable command) {
      long now = data.consumeLong();
      long delay = data.consumeLong(0, Long.MAX_VALUE);
      int prevCount = state.callCount;

      pacer.schedule(executor, command, now, delay);

      if (state.callCount > prevCount) {
        // A new schedule was made; verify the minimum delay invariant
        assertWithMessage("Scheduled delay should be >= TOLERANCE (actual=%s)", state.lastDelay)
            .that(state.lastDelay).isAtLeast(Pacer.TOLERANCE);

        if (delay <= Pacer.TOLERANCE) {
          assertWithMessage("nextFireTime for small delay")
              .that(pacer.nextFireTime).isEqualTo(now + Pacer.TOLERANCE);
          assertWithMessage("actual delay for small delay")
              .that(state.lastDelay).isEqualTo(Pacer.TOLERANCE);
        } else {
          assertWithMessage("nextFireTime for large delay")
              .that(pacer.nextFireTime).isEqualTo(now + delay);
          assertWithMessage("actual delay for large delay")
              .that(state.lastDelay).isEqualTo(delay);
        }
        assertWithMessage("future should be set after schedule")
            .that(pacer.future).isSameInstanceAs(state.lastFuture);
      }
    }

    /** Cancels a pending schedule and verifies the state is fully reset. */
    private static void cancel(Pacer pacer) {
      pacer.cancel();
      assertWithMessage("nextFireTime should be 0 after cancel")
          .that(pacer.nextFireTime).isEqualTo(0L);
      assertWithMessage("future should be null after cancel")
          .that(pacer.future).isNull();
      assertWithMessage("isScheduled should be false after cancel")
          .that(pacer.isScheduled()).isFalse();
    }

    /** Completes the current future, simulating a scheduled task finishing. */
    @SuppressWarnings("FutureReturnValueIgnored")
    private static void completeFuture(Pacer pacer) {
      if (pacer.future instanceof CompletableFuture) {
        ((CompletableFuture<?>) pacer.future).complete(null);
      }
    }

    /** A scheduler implementation that records the last invocation for verification. */
    private static final class SchedulerState implements Scheduler {
      @Nullable Future<?> lastFuture;
      long lastDelay;
      int callCount;

      @Override
      public Future<?> schedule(Executor executor, Runnable command,
          long delay, TimeUnit unit) {
        lastFuture = new CompletableFuture<>();
        lastDelay = delay;
        callCount++;
        return lastFuture;
      }
    }

    private enum Operation { SCHEDULE, CANCEL, COMPLETE_FUTURE }
  }

  @Nested
  static final class MaySkipTest {

    @FuzzTest(maxDuration = "5m")
    void maySkip(FuzzedDataProvider data) {
      var pacer = new Pacer(DisabledScheduler.INSTANCE);

      long nextFireTime = data.consumeLong();
      // In practice, scheduleAt and nextFireTime are derived from nearby System.nanoTime() values
      // with bounded delays. The delta range extends well beyond TOLERANCE to test the boundary.
      long delta = data.consumeLong(-Pacer.TOLERANCE * 4, Pacer.TOLERANCE * 4);
      long scheduleAt = nextFireTime + delta;
      pacer.nextFireTime = nextFireTime;

      boolean result = pacer.maySkip(scheduleAt);

      // Two's complement: (nextFireTime + delta) - nextFireTime == delta, even on overflow.
      // So the subtraction in maySkip always recovers the exact delta for bounded inputs.
      boolean expected = (delta >= 0) || (-delta <= Pacer.TOLERANCE);

      assertWithMessage("maySkip(scheduleAt=%s) with nextFireTime=%s: delta=%s",
          scheduleAt, nextFireTime, delta).that(result).isEqualTo(expected);
    }
  }
}
