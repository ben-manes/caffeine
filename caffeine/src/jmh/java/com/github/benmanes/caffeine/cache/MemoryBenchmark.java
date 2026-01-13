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
package com.github.benmanes.caffeine.cache;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.math.RoundingMode.HALF_EVEN;
import static java.util.Locale.US;
import static java.util.function.Function.identity;

import java.time.Duration;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;

import org.github.jamm.MemoryMeter;
import org.openjdk.jol.info.GraphLayout;

import com.google.common.base.Functions;
import com.google.common.base.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.math.LongMath;
import com.google.errorprone.annotations.Var;
import com.jakewharton.fliptables.FlipTable;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A non-JMH benchmark to compare the memory overhead of different cache implementations. Note that
 * the measurements estimate based on the current JVM configuration, e.g. 64-bit with compressed
 * references if the benchmark is executed with a heap under 32GB. This can mean that object
 * padding may or may not have a visible effect.
 * <p>
 * This benchmark requires a JavaAgent to evaluate the object sizes and can be executed using:
 * {@snippet lang="shell" :
 * ./gradlew memoryOverhead -q --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"LexicographicalAnnotationAttributeListing", "JavadocDeclaration",
    "MemberName", "PMD.MethodNamingConventions", "SystemOut"})
public final class MemoryBenchmark {
  // The number of entries added to minimize skew due to non-entry factors
  static final int FUZZY_SIZE = 25_000;
  // The maximum size, which is larger than the fuzzy factor due to Guava's early eviction
  static final int MAXIMUM_SIZE = 2 * FUZZY_SIZE;
  // The pre-computed entries to store into the cache when computing the per-entry overhead
  static final ImmutableMap<Integer, Integer> workingSet = IntStream.range(0, FUZZY_SIZE)
      .boxed().collect(toImmutableMap(identity(), i -> -i));
  static final ImmutableList<String> HEADER = ImmutableList.of(
      "Cache",
      "Baseline (MemoryMeter)", "Per Entry (MemoryMeter)",
      "Baseline (ObjectLayout)", "Per Entry (ObjectLayout)");

  final MemoryMeter meter = MemoryMeter.builder().measureNonStrongReferences().build();
  final ToLongFunction<Object> layout = object -> GraphLayout.parseInstance(object).totalSize();

  public void run() {
    if (!MemoryMeter.hasInstrumentation()) {
      System.out.println("WARNING: Java agent not installed - guessing instead");
    }
    System.out.println();
    unbounded();
    maximumSize();
    maximumSize_expireAfterAccess();
    maximumSize_expireAfterWrite();
    maximumSize_refreshAfterWrite();
    maximumWeight();
    expireAfterAccess();
    expireAfterWrite();
    expireAfterAccess_expireAfterWrite();
    weakKeys();
    weakValues();
    weakKeys_weakValues();
    weakKeys_softValues();
    softValues();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Caffeine<Integer, Integer> caffeineBuilder() {
    // Avoid counting ForkJoinPool in estimates
    return (Caffeine) Caffeine.newBuilder().executor(Runnable::run);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static CacheBuilder<Integer, Integer> guavaBuilder() {
    return (CacheBuilder) CacheBuilder.newBuilder();
  }

  private void unbounded() {
    var caffeine = caffeineBuilder();
    var guava = guavaBuilder();
    compare("Unbounded", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void maximumSize() {
    var caffeine = caffeineBuilder().maximumSize(MAXIMUM_SIZE);
    var guava = guavaBuilder().maximumSize(MAXIMUM_SIZE);
    compare("Maximum Size", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void maximumWeight() {
    var caffeine = caffeineBuilder().maximumWeight(MAXIMUM_SIZE).weigher((k, v) -> 1);
    var guava = guavaBuilder().maximumWeight(MAXIMUM_SIZE).weigher((k, v) -> 1);
    compare("Maximum Weight", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void maximumSize_expireAfterAccess() {
    var caffeine = caffeineBuilder()
        .expireAfterAccess(Duration.ofMinutes(1))
        .maximumSize(MAXIMUM_SIZE);
    var guava = guavaBuilder()
        .expireAfterAccess(Duration.ofMinutes(1))
        .maximumSize(MAXIMUM_SIZE);
    compare("Maximum Size & Expire after Access",
        () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void maximumSize_expireAfterWrite() {
    var caffeine = caffeineBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .maximumSize(MAXIMUM_SIZE);
    var guava = guavaBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .maximumSize(MAXIMUM_SIZE);
    compare("Maximum Size & Expire after Write",
        () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void maximumSize_refreshAfterWrite() {
    var caffeine = caffeineBuilder()
        .refreshAfterWrite(Duration.ofMinutes(1))
        .maximumSize(MAXIMUM_SIZE);
    var guava = guavaBuilder()
        .refreshAfterWrite(Duration.ofMinutes(1))
        .maximumSize(MAXIMUM_SIZE);
    compare("Maximum Size & Refresh after Write", () -> caffeine.build(k -> k).asMap(),
        () -> guava.build(CacheLoader.from(Functions.identity())).asMap());
  }

  private void expireAfterAccess() {
    var caffeine = caffeineBuilder().expireAfterAccess(Duration.ofMinutes(1));
    var guava = guavaBuilder().expireAfterAccess(Duration.ofMinutes(1));
    compare("Expire after Access", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void expireAfterWrite() {
    var caffeine = caffeineBuilder().expireAfterWrite(Duration.ofMinutes(1));
    var guava = guavaBuilder().expireAfterWrite(Duration.ofMinutes(1));
    compare("Expire after Write", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void expireAfterAccess_expireAfterWrite() {
    var caffeine = caffeineBuilder()
        .expireAfterAccess(Duration.ofMinutes(1))
        .expireAfterWrite(Duration.ofMinutes(1));
    var guava = guavaBuilder()
        .expireAfterAccess(Duration.ofMinutes(1))
        .expireAfterWrite(Duration.ofMinutes(1));
    compare("Expire after Access & after Write",
        () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void weakKeys() {
    var caffeine = caffeineBuilder().weakKeys();
    var guava = guavaBuilder().weakKeys();
    compare("Weak Keys", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void weakValues() {
    var caffeine = caffeineBuilder().weakValues();
    var guava = guavaBuilder().weakValues();
    compare("Weak Values", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void weakKeys_weakValues() {
    var caffeine = caffeineBuilder().weakKeys().weakValues();
    var guava = guavaBuilder().weakKeys().weakValues();
    compare("Weak Keys & Weak Values", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void weakKeys_softValues() {
    var caffeine = caffeineBuilder().weakKeys().softValues();
    var guava = guavaBuilder().weakKeys().softValues();
    compare("Weak Keys & Soft Values", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  private void softValues() {
    var caffeine = caffeineBuilder().softValues();
    var guava = guavaBuilder().softValues();
    compare("Soft Values", () -> caffeine.build().asMap(), () -> guava.build().asMap());
  }

  @SuppressFBWarnings("FORMAT_STRING_MANIPULATION")
  private void compare(String label,
      Supplier<Map<Integer, Integer>> caffeine, Supplier<Map<Integer, Integer>> guava) {
    String result = FlipTable.of(HEADER.toArray(String[]::new), new String[][] {
      formatRow("Caffeine", evaluate(caffeine, meter::measureDeep), evaluate(caffeine, layout)),
      formatRow("Guava", evaluate(guava, meter::measureDeep), evaluate(guava, layout))
    });
    int leftPadded = Math.max((116 - label.length()) / 2 - 1, 1);
    System.out.printf(US, " %2$-" + leftPadded + "s %s%n", label, " ");
    System.out.println(result);
  }

  private static Evaluation evaluate(Supplier<Map<Integer, Integer>> supplier,
      ToLongFunction<Object> evaluator) {
    var map = supplier.get();
    long initial = evaluator.applyAsLong(map);

    map.putAll(workingSet);
    long populated = evaluator.applyAsLong(map);

    map.clear();
    long base = evaluator.applyAsLong(map);

    long entryOverhead = 2 * FUZZY_SIZE
        * evaluator.applyAsLong(workingSet.keySet().iterator().next());
    @Var long perEntry = LongMath.divide(populated - entryOverhead - base, FUZZY_SIZE, HALF_EVEN);
    perEntry += ((perEntry & 1) == 0) ? 0 : 1;
    long aligned = ((perEntry % 8) == 0) ? perEntry : ((1 + perEntry / 8) * 8);

    return new Evaluation(initial, perEntry, aligned);
  }

  private static String[] formatRow(String label, Evaluation memoryMeter, Evaluation layout) {
    return new String[] {
        label,
        String.format(US, "%,d bytes", memoryMeter.base),
        String.format(US, "%,d bytes (%,d aligned)", memoryMeter.perEntry, memoryMeter.aligned),
        String.format(US, "%,d bytes", layout.base),
        String.format(US, "%,d bytes (%,d aligned)", layout.perEntry, layout.aligned)
    };
  }

  public static void main(String[] args) {
    new MemoryBenchmark().run();
  }

  private static final class Evaluation {
    final long perEntry;
    final long aligned;
    final long base;

    Evaluation(long base, long perEntry, long aligned) {
      this.perEntry = perEntry;
      this.aligned = aligned;
      this.base = base;
    }
  }
}
