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

import static com.github.benmanes.caffeine.cache.LincheckOptions.modelChecking;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Test;

/**
 * Lincheck test for the lock-free {@code Pacer#isScheduled()} fast-path read.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class PacerLincheckTest {

  /**
   * The first {@code rescheduleCleanUpIfIncomplete} guard calls {@code isScheduled()} without
   * holding the eviction lock, racing a maintenance cycle that nulls {@code future} via
   * {@code cancel()}. If {@code isScheduled()} reads the field twice, the model checker can land the
   * null write between the reads so the second dereference throws a {@link NullPointerException}.
   */
  @Test
  @SuppressWarnings("CheckReturnValue")
  void isScheduled_concurrentCancel() {
    Lincheck.runConcurrentTest(modelChecking().invocationsPerIteration, () -> {
      var pacer = new Pacer(Scheduler.disabledScheduler());
      pacer.future = new CompletableFuture<>();

      var failure = new AtomicReference<Throwable>();
      var reader = new Thread(() -> {
        try {
          pacer.isScheduled();
        } catch (Throwable t) {
          failure.set(t);
        }
      });
      var canceller = new Thread(pacer::cancel);
      try {
        reader.start();
        canceller.start();
        reader.join();
        canceller.join();
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
      if (failure.get() != null) {
        throw new AssertionError("isScheduled() must not throw", failure.get());
      }
    });
  }
}
