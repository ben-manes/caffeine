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

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions;
import org.testng.annotations.Test;

/**
 * Linearizability checks for {@link MpscGrowableArrayQueue}. This tests the JCTools' version until
 * our copy is resync'd due to requiring an iterator for reporting the state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Param(name = "element", gen = IntGen.class, conf = "1:5")
public final class MpscGrowableArrayQueueLincheckTest {
  private final Queue<Integer> queue;

  public MpscGrowableArrayQueueLincheckTest() {
    queue = new org.jctools.queues.MpscGrowableArrayQueue<>(4, 65_536);
  }

  @Operation
  public boolean offer(@Param(name = "element") int e) {
    return queue.offer(e);
  }

  @Operation(nonParallelGroup = "consumer")
  public Integer poll() {
    return queue.poll();
  }

  /**
   * This test checks that the concurrent queue is linearizable with bounded model checking. Unlike
   * stress testing, this approach can also provide a trace of an incorrect execution. However, it
   * uses sequential consistency model, so it can not find any low-level bugs (e.g., missing
   * 'volatile'), and thus, it is recommended to have both test modes.
   * <p>
   * This test requires the following JVM arguments,
   * <ul>
   *   <li>--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
   *   <li>--add-exports java.base/jdk.internal.util=ALL-UNNAMED
   * </ul>
   */
  @Test(groups = "lincheck")
  public void modelCheckingTest() {
    var options = new ModelCheckingOptions()
        .iterations(100)                  // the number of different scenarios
        .invocationsPerIteration(10_000); // how deeply each scenario is tested
    new LinChecker(getClass(), options).check();
  }

  /** This test checks that the concurrent queue is linearizable with stress testing. */
  @Test(groups = "lincheck")
  public void stressTest() {
    var options = new StressOptions()
        .iterations(100)                  // the number of different scenarios
        .invocationsPerIteration(10_000); // how deeply each scenario is tested
    new LinChecker(getClass(), options).check();
  }
}
