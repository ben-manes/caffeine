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
package com.github.benmanes.caffeine.testing;

import java.lang.ref.Cleaner;
import java.lang.ref.SoftReference;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;

import com.google.common.testing.GcFinalization;
import com.google.errorprone.annotations.Var;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Awaits {
  private static final Duration ONE_MILLISECOND = Duration.ofMillis(1);
  private static final Cleaner CLEANER = Cleaner.create();

  private Awaits() {}

  /** Returns a configured {@link ConditionFactory} that polls at a short interval. */
  public static ConditionFactory await() {
    return Awaitility.with()
        .pollDelay(ONE_MILLISECOND)
        .pollInterval(ONE_MILLISECOND)
        .pollExecutorService(ConcurrentTestHarness.executor);
  }

  /**
   * Tries to perform a garbage collection cycle that includes the processing of weak and soft
   * references.
   */
  @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE_OF_NULL")
  @SuppressWarnings({"PMD.UnusedAssignment", "UnusedAssignment"})
  public static void awaitFullGc() {
    @Var var target = new Object();
    var cleaned = new CountDownLatch(1);
    var ref = new SoftReference<>(target);
    CLEANER.register(target, cleaned::countDown);
    target = null;

    GcFinalization.awaitFullGc();
    GcFinalization.await(cleaned);
    GcFinalization.awaitDone(() -> ref.get() == null);
  }
}
