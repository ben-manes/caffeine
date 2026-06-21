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
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Test;

/**
 * Model-checking mirror of {@code RefreshAfterWriteFrayTest} for the refresh completion race. The
 * declarative {@link AbstractLincheckCacheTest} excludes refresh because it is not linearizable, so
 * these use {@code runConcurrentTest} to drive a fixed scenario and assert the subsystem's own
 * invariants (no lost reload, no stampede) rather than linearizability.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class RefreshAfterWriteLincheckTest {

  /**
   * Two reads race the refresh of a due entry. With an incrementing loader the applied value equals
   * the number of loads, so a discarded reload shows up as {@code value < loadCount} and a stampede
   * as a second load. The completion's write-time check must ignore the transient soft-lock marker
   * a concurrent reader sets while probing, and the token must stay registered across the value
   * swap.
   * <p>
   * Model checking reliably reproduces the completion-side regression (the discarded reload and the
   * remove-then-refresh stampede). It does not, however, reach the rarer entry-path straggler — a
   * reader descheduled across an <em>entire</em> concurrent refresh completion before its own
   * {@code computeIfAbsent} re-validates the soft-lock — even at 10k invocations, because that
   * interleaving exceeds the bounded context-switch search. The {@code RefreshAfterWriteFrayTest}
   * sibling (randomized scheduling) is the reliable guard for that case.
   */
  @Test
  @SuppressWarnings("CheckReturnValue")
  void completion_notDiscardedNorStampeded() {
    Lincheck.runConcurrentTest(modelChecking().invocationsPerIteration, () -> {
      var nanos = new AtomicLong();
      var loadCount = new AtomicInteger();
      LoadingCache<Integer, Integer> cache = Caffeine.newBuilder()
          .refreshAfterWrite(Duration.ofMinutes(1))
          .executor(Runnable::run)
          .ticker(nanos::get)
          .maximumSize(10)
          .build(key -> loadCount.incrementAndGet());
      cache.get(1);
      nanos.set(Duration.ofMinutes(2).toNanos());

      var failure = new AtomicReference<Throwable>();
      Runnable read = () -> {
        try {
          cache.get(1);
        } catch (Throwable t) {
          failure.set(t);
        }
      };
      var threadA = new Thread(read);
      var threadB = new Thread(read);
      try {
        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
      if (failure.get() != null) {
        fail("get(1) must not throw", failure.get());
      }
      cache.cleanUp();

      var value = cache.getIfPresent(1);
      int loads = loadCount.get();
      if (loads > 2) {
        fail("at most one refresh should run (no stampede), loadCount=" + loads);
      }
      if ((value == null) || (value.intValue() != loads)) {
        fail("a completed reload must be applied, not discarded: "
            + "value=" + value + " loads=" + loads);
      }
    });
  }
}
