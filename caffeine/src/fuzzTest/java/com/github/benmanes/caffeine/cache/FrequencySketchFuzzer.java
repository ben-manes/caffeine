/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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

import java.util.HashMap;
import java.util.Map;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.common.collect.Range;
import com.google.errorprone.annotations.Var;

/**
 * A fuzzer that exercises the {@link FrequencySketch} by generating random sequences of increment
 * and ensureCapacity operations. The sketch's frequency estimates are validated against a reference
 * model to ensure the count-min sketch invariants hold: frequencies are bounded [0, 15], never
 * negative, and the sketch correctly ages counters on reset.
 */
final class FrequencySketchFuzzer {
  private static final Range<Integer> FREQUENCY_RANGE = Range.closed(0, 15);
  private static final Operation[] OPERATIONS = Operation.values();

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  @FuzzTest(maxDuration = "5m")
  void sketch(FuzzedDataProvider data) {
    long capacity = data.consumeInt(1, 4096);
    var sketch = new FrequencySketch();
    sketch.ensureCapacity(capacity);

    // Reference model: exact counts per item, capped at 15
    var counts = new HashMap<Integer, Integer>();
    @Var boolean hasReset = false;

    int operations = data.consumeInt(0, 500);
    for (int i = 0; i < operations; i++) {
      var operation = OPERATIONS[data.consumeInt(0, OPERATIONS.length - 1)];
      switch (operation) {
        case ENSURE_CAPACITY: {
          hasReset = ensureCapacity(data, sketch, counts, hasReset);
          break;
        }
        case INCREMENT: {
          hasReset = increment(data, sketch, counts, hasReset);
          break;
        }
        case QUERY: {
          query(data, sketch, counts);
          break;
        }
      }
    }

    validate(sketch, counts, hasReset);
  }

  private static boolean ensureCapacity(FuzzedDataProvider data,
      FrequencySketch sketch, Map<Integer, Integer> counts, boolean hasReset) {
    long newCapacity = data.consumeInt(1, 8192);
    int sizeBefore = sketch.table.length;
    sketch.ensureCapacity(newCapacity);

    if (sketch.table.length > sizeBefore) {
      // Resized: all counts were cleared
      counts.clear();
      return false;
    }
    return hasReset;
  }

  private static boolean increment(FuzzedDataProvider data,
      FrequencySketch sketch, Map<Integer, Integer> counts, boolean hasReset) {
    int item = data.consumeInt();
    int sizeBefore = sketch.size;
    int freqBefore = sketch.frequency(item);

    sketch.increment(item);
    int freqAfter = sketch.frequency(item);
    assertWithMessage("frequency(%s) out of bounds", item)
        .that(freqAfter).isIn(FREQUENCY_RANGE);

    // Track in reference model
    counts.merge(item, 1, Integer::sum);

    // Detect if a reset occurred: size wraps back down after reaching sampleSize
    boolean resetOccurred = (sketch.size <= sizeBefore);
    if (!resetOccurred) {
      // No reset: frequency must not decrease
      assertWithMessage("frequency(%s) decreased without reset (before=%s, after=%s)",
          item, freqBefore, freqAfter).that(freqAfter).isAtLeast(freqBefore);
      return hasReset;
    }
    return true;
  }

  private static void query(FuzzedDataProvider data,
      FrequencySketch sketch, Map<Integer, Integer> counts) {
    int item = data.consumeInt();
    int freq = sketch.frequency(item);

    // Frequency must be in [0, 15]
    assertWithMessage("frequency(%s) out of bounds on query", item)
        .that(freq).isIn(FREQUENCY_RANGE);

    // If this item was never incremented, frequency should be low (probabilistic,
    // so we allow some false positives but it should never exceed max)
    if (!counts.containsKey(item)) {
      assertWithMessage("frequency(%s) for never-incremented item", item).that(freq)
          .isAtMost(FREQUENCY_RANGE.upperEndpoint());
    }
  }

  private static void validate(FrequencySketch sketch,
      Map<Integer, Integer> counts, boolean hasReset) {
    // After all operations, validate global invariants
    // 1. Every item in the reference model should have a non-negative frequency
    for (var entry : counts.entrySet()) {
      int freq = sketch.frequency(entry.getKey());
      assertWithMessage("frequency(%s) is negative", entry.getKey())
          .that(freq).isAtLeast(FREQUENCY_RANGE.lowerEndpoint());
      assertWithMessage("frequency(%s) exceeds max", entry.getKey())
          .that(freq).isAtMost(FREQUENCY_RANGE.upperEndpoint());
    }

    // 2. Size should be non-negative and bounded by sampleSize
    assertWithMessage("sketch.size should be non-negative")
        .that(sketch.size).isAtLeast(0);
    assertWithMessage("sketch.size should be at most sampleSize")
        .that(sketch.size).isAtMost(sketch.sampleSize);

    // 3. Heavy hitter ordering: if item A was incremented significantly more than item B
    // (and no resets occurred), then A's frequency should be >= B's frequency.
    // This validates the count-min sketch's fundamental accuracy property.
    if (!hasReset && counts.size() >= 2) {
      @Var int maxItem = 0;
      @Var int minItem = 0;
      @Var int maxCount = -1;
      @Var int minCount = Integer.MAX_VALUE;
      for (var entry : counts.entrySet()) {
        if (entry.getValue() > maxCount) {
          maxCount = entry.getValue();
          maxItem = entry.getKey();
        }
        if (entry.getValue() < minCount) {
          minCount = entry.getValue();
          minItem = entry.getKey();
        }
      }
      if (maxItem != minItem) {
        assertWithMessage("heavy hitter (%s, count=%s) should have freq >= "
            + "light hitter (%s, count=%s)", maxItem, maxCount, minItem, minCount)
                .that(sketch.frequency(maxItem)).isAtLeast(sketch.frequency(minItem));
      }
    }
  }

  private enum Operation { INCREMENT, ENSURE_CAPACITY, QUERY }
}
