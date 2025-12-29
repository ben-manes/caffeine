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

import static java.util.Objects.requireNonNullElse;

import java.util.Map;

import org.jetbrains.lincheck.datastructures.IntGen;
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.Param;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

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
@SuppressWarnings({"MemberName", "PMD.AbstractClassWithoutAbstractMethod"})
abstract class AbstractLincheckCacheTest {
  private final LoadingCache<Integer, Integer> cache;

  protected AbstractLincheckCacheTest(Caffeine<Object, Object> builder) {
    cache = builder.executor(Runnable::run).build(key -> -key);
  }

  @Test
  void modelCheckingTest() {
    var options = LincheckOptions.modelChecking();
    new ModelCheckingOptions()
        .iterations(options.iterations)
        .invocationsPerIteration(options.invocationsPerIteration)
        .check(getClass());
  }

  @Test
  void stressTest() {
    var options = LincheckOptions.stress();
    new StressOptions()
        .iterations(options.iterations)
        .invocationsPerIteration(options.invocationsPerIteration)
        .check(getClass());
  }

  /* --------------- Cache --------------- */

  @Operation
  public @Nullable Integer getIfPresent(@Param(name = "key") int key) {
    return cache.getIfPresent(key);
  }

  @Operation
  public @Nullable Integer get_function(
      @Param(name = "key") int key, @Param(name = "value") int nextValue) {
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
  public @Nullable Integer get(@Param(name = "key") int key) {
    return cache.get(key);
  }

  /* --------------- Concurrent Map --------------- */

  @Operation
  public boolean containsKey(@Param(name = "key") int key) {
    return cache.asMap().containsKey(key);
  }

  @Operation
  public @Nullable Integer get_asMap(@Param(name = "key") int key) {
    return cache.asMap().get(key);
  }

  @Operation
  public @Nullable Integer put_asMap(
      @Param(name = "key") int key, @Param(name = "value") int value) {
    return cache.asMap().put(key, value);
  }

  @Operation
  public @Nullable Integer putIfAbsent(
      @Param(name = "key") int key, @Param(name = "value") int value) {
    return cache.asMap().putIfAbsent(key, value);
  }

  @Operation
  public @Nullable Integer replace(
      @Param(name = "key") int key, @Param(name = "value") int nextValue) {
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
  public @Nullable Integer computeIfPresent(@Param(name = "key") int key,
      @Param(name = "value") int nextValue) {
    return cache.asMap().computeIfPresent(key, (k, v) -> v + nextValue);
  }

  @Operation
  public Integer compute(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return cache.asMap().compute(key, (k, v) -> requireNonNullElse(v, 0) + nextValue);
  }

  @Operation
  public Integer merge(@Param(name = "key") int key, @Param(name = "value") int nextValue) {
    return cache.asMap().merge(key, nextValue, (k, v) -> v + nextValue);
  }
}
