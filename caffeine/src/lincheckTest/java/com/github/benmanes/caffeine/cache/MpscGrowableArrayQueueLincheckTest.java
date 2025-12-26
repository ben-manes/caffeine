/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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

import java.util.Queue;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Linearizability checks for {@link MpscGrowableArrayQueue}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"JUnitClassModifiers", "PMD.JUnit5TestShouldBePackagePrivate"})
@Param(name = "element", gen = IntGen.class, conf = "1:5")
public final class MpscGrowableArrayQueueLincheckTest {
  private final Queue<Integer> queue;

  public MpscGrowableArrayQueueLincheckTest() {
    queue = new MpscGrowableArrayQueue<>(4, 65_536);
  }

  @Operation
  public boolean offer(@Param(name = "element") int e) {
    return queue.offer(e);
  }

  @Operation(nonParallelGroup = "consumer")
  public @Nullable Integer poll() {
    return queue.poll();
  }

  /**
   * This test checks that the concurrent queue is linearizable with bounded model checking. Unlike
   * stress testing, this approach can also provide a trace of an incorrect execution. However, it
   * uses sequential consistency model, so it cannot find any low-level bugs (e.g., missing
   * 'volatile'), and thus, it is recommended to have both test modes.
   */
  @Test
  void modelCheckingTest() {
    new ModelCheckingOptions()
        .iterations(100)                 // the number of different scenarios
        .invocationsPerIteration(10_000) // how deeply each scenario is tested
        .check(getClass());
  }

  /** This test checks that the concurrent queue is linearizable with stress testing. */
  @Test
  void stressTest() {
    new StressOptions()
        .iterations(100)                 // the number of different scenarios
        .invocationsPerIteration(10_000) // how deeply each scenario is tested
        .check(getClass());
  }
}
