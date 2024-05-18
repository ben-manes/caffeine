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
package com.github.benmanes.caffeine.lincheck;

import static org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD;

import java.util.Map;

import org.jetbrains.kotlinx.lincheck.LinChecker;
import org.jetbrains.kotlinx.lincheck.annotations.Operation;
import org.jetbrains.kotlinx.lincheck.annotations.Param;
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions;
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

/**
 * Linearizability checks. This property is guaranteed for per-key operations in the absence of
 * evictions, refresh, and other non-deterministic features. This property is not supported by
 * operations that span across multiple entries such as {@link Map#isEmpty()}, {@link Map#size()},
 * and {@link Map#clear()}.
 *
 * @author afedorov2602@gmail.com (Alexander Fedorov)
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Param(name = "key", gen = IntGen.class, conf = "1:5")
@Param(name = "value", gen = IntGen.class, conf = "1:10")
public abstract class AbstractLincheckCacheTest {
  private final LoadingCache<Integer, Integer> cache;

  public AbstractLincheckCacheTest(Caffeine<Object, Object> builder) {
    cache = builder.executor(Runnable::run).build(key -> -key);
  }

  /**
   * This test checks linearizability with bounded model checking. Unlike stress testing, this
   * approach can also provide a trace of an incorrect execution. However, it uses sequential
   * consistency model, so it can not find any low-level bugs (e.g., missing 'volatile'), and thus,
   * it is recommended to have both test modes.
   */
  @Test(groups = "lincheck")
  public void modelCheckingTest() {
    var options = new ModelCheckingOptions()
        .iterations(100)                // the number of different scenarios
        .invocationsPerIteration(1_000) // how deeply each scenario is tested
        .hangingDetectionThreshold(5 * DEFAULT_HANGING_DETECTION_THRESHOLD);
    LinChecker.check(getClass(), options);
  }

  /** This test checks linearizability with stress testing. */
  @Test(groups = "lincheck")
  public void stressTest() {
    var options = new StressOptions()
        .iterations(100)                  // the number of different scenarios
        .invocationsPerIteration(10_000); // how deeply each scenario is tested
    LinChecker.check(getClass(), options);
  }

  /* --------------- Cache --------------- */

  @Operation
  public Integer getIfPresent(@Param(name = "key") int key) {
    return cache.getIfPresent(key);
  }

  @Operation
  public Integer get_function(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return cache.get(key, k -> nextValue);
  }

  @Operation
  public void put(@Param(name = "key") int key, @Param(name = "value") int value) {
    cache.put(key, value);
  }

  @Operation
  public void invalidate(@Param(name = "key") int key) {
    cache.invalidate(key);
  }

  /* --------------- LoadingCache --------------- */

  @Operation
  public Integer get(@Param(name = "key") int key) {
    return cache.get(key);
  }

  /* --------------- Concurrent Map --------------- */

  @Operation
  public boolean containsKey(@Param(name = "key") int key) {
    return cache.asMap().containsKey(key);
  }

  @Operation
  public Integer get_asMap(@Param(name = "key") int key) {
    return cache.asMap().get(key);
  }

  @Operation
  public Integer put_asMap(@Param(name = "key") int key, @Param(name = "value") int value) {
    return cache.asMap().put(key, value);
  }

  @Operation
  public Integer putIfAbsent(@Param(name = "key") int key, @Param(name = "value") int value) {
    return cache.asMap().putIfAbsent(key, value);
  }

  @Operation
  public Integer replace(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return cache.asMap().replace(key, nextValue);
  }

  @Operation
  public boolean replaceConditionally(@Param(name = "key") int key,
      @Param(name = "value") int previousValue, @Param(name = "value") int nextValue) {
    return cache.asMap().replace(key, previousValue, nextValue);
  }

  @Operation
  public Integer remove(@Param(name = "key") int key) {
    return cache.asMap().remove(key);
  }

  @Operation
  public boolean removeConditionally(@Param(name = "key") int key,
      @Param(name = "value") int previousValue) {
    return cache.asMap().remove(key, previousValue);
  }

  @Operation
  public Integer computeIfAbsent(@Param(name = "key") int key,
      @Param(name = "value") int nextValue) {
    return cache.asMap().computeIfAbsent(key, k -> nextValue);
  }

  @Operation
  public Integer computeIfPresent(@Param(name = "key") int key,
      @Param(name = "value") int nextValue) {
    return cache.asMap().computeIfPresent(key, (k, v) -> nextValue);
  }

  @Operation
  public Integer compute(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return cache.asMap().merge(key, nextValue, (oldValue, newValue) -> oldValue + newValue);
  }

  @Operation
  public Integer merge(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return cache.asMap().merge(key, nextValue, (k, v) -> v + nextValue);
  }
}
