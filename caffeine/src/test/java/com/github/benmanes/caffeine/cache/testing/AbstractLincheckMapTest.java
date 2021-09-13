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
package com.github.benmanes.caffeine.cache.testing;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions;
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState;
import org.testng.annotations.Test;

/**
 * Linearizability checks for a concurrent map.
 *
 * @author afedorov2602@gmail.com (Alexander Fedorov)
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Param(name = "key", gen = IntGen.class, conf = "1:5")
@Param(name = "value", gen = IntGen.class, conf = "1:10")
public abstract class AbstractLincheckMapTest extends VerifierState {
  private final ConcurrentMap<Integer, Integer> map;

  public AbstractLincheckMapTest() {
    map = create();
  }

  /** Returns the map instance to test against. */
  protected abstract ConcurrentMap<Integer, Integer> create();

  @Operation
  public boolean containsKey(@Param(name = "key") int key) {
    return map.containsKey(key);
  }

  @Operation
  public boolean containsValue(@Param(name = "value") int value) {
    return map.containsValue(value);
  }

  @Operation
  public Integer get(@Param(name = "key") int key) {
    return map.get(key);
  }

  @Operation
  public Integer put(@Param(name = "key") int key, @Param(name = "value") int value) {
    return map.put(key, value);
  }

  @Operation
  public Integer putIfAbsent(@Param(name = "key") int key, @Param(name = "value") int value) {
    return map.putIfAbsent(key, value);
  }

  @Operation
  public Integer replace(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return map.replace(key, nextValue);
  }

  @Operation
  public boolean replaceConditionally(@Param(name = "key") int key,
      @Param(name = "value") int previousValue, @Param(name = "value") int nextValue) {
    return map.replace(key, previousValue, nextValue);
  }

  @Operation
  public Integer remove(@Param(name = "key") int key) {
    return map.remove(key);
  }

  @Operation
  public boolean removeConditionally(@Param(name = "key") int key,
      @Param(name = "value") int previousValue) {
    return map.remove(key, previousValue);
  }

  @Operation
  public Integer computeIfAbsent(@Param(name = "key") int key,
      @Param(name = "value") int nextValue) {
    return map.computeIfAbsent(key, k -> nextValue);
  }

  @Operation
  public Integer computeIfPresent(@Param(name = "key") int key,
      @Param(name = "value") int nextValue) {
    return map.computeIfPresent(key, (k, v) -> nextValue);
  }

  @Operation
  public Integer compute(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return map.compute(key, (k, v) -> nextValue);
  }

  @Operation
  public Integer merge(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return map.merge(key, nextValue, (k, v) -> v + nextValue);
  }

  /**
   * This test checks that the concurrent map is linearizable with bounded model checking. Unlike
   * stress testing, this approach can also provide a trace of an incorrect execution. However, it
   * uses sequential consistency model, so it can not find any low-level bugs (e.g., missing
   * 'volatile'), and thus, it it recommended to have both test modes.
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
        .iterations(100)                 // the number of different scenarios
        .invocationsPerIteration(1_000); // how deeply each scenario is tested
    new LinChecker(getClass(), options).check();
  }

  /** This test checks that the concurrent map is linearizable with stress testing. */
  @Test(groups = "lincheck")
  public void stressTest() {
    var options = new StressOptions()
        .iterations(100)                  // the number of different scenarios
        .invocationsPerIteration(10_000); // how deeply each scenario is tested
    new LinChecker(getClass(), options).check();
  }

  /**
   * Provides something with correct <tt>equals</tt> and <tt>hashCode</tt> methods that can be
   * interpreted as an internal data structure state for faster verification. The only limitation is
   * that it should be different for different data structure states. For {@link Map} it itself is
   * used.
   *
   * @return object representing internal state
   */
  @Override
  protected Object extractState() {
    return map;
  }
}
