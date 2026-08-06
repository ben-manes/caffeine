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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import com.google.errorprone.annotations.Var;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ClassEscapesDefinedScope")
final class FrequencySketchTest {
  private final Integer item = ThreadLocalRandom.current().nextInt();

  @Test
  void construct() {
    var sketch = new FrequencySketch();
    assertThat(sketch.table).isNull();
    assertThat(sketch.isNotInitialized()).isTrue();

    sketch.increment(item);
    assertThat(sketch.frequency(item)).isEqualTo(0);
  }

  @Test
  void ensureCapacity_negative() {
    var sketch = makeSketch(512);
    assertThrows(IllegalArgumentException.class, () -> sketch.ensureCapacity(-1));
  }

  @Test
  void ensureCapacity_smaller() {
    var sketch = makeSketch(512);
    int size = sketch.table.length;
    sketch.ensureCapacity(size / 2);
    assertThat(sketch.table).hasLength(size);
    assertThat(sketch.sampleSize).isEqualTo(10 * size / 2);
    assertThat(sketch.blockMask).isEqualTo((size >> 3) - 1);
  }

  @Test
  void ensureCapacity_shrink_resetReachable() {
    var sketch = makeSketch(1_024);
    sketch.size = 5_000;

    // The table is retained but the aging period tracks the new maximum, with the observed
    // count clamped so that the equality reset test remains reachable
    sketch.ensureCapacity(256);
    assertThat(sketch.table).hasLength(1_024);
    assertThat(sketch.sampleSize).isEqualTo(2_560);
    assertThat(sketch.size).isEqualTo(2_559);

    // the next observed addition reaches the sample threshold and triggers the aging cycle
    sketch.increment(item);
    assertThat(sketch.size).isEqualTo(1_279);
  }

  @Test
  void ensureCapacity_shrink_denseTable_agesOnSchedule() {
    var sketch = makeSketch(1_024);
    Arrays.fill(sketch.table, FrequencySketch.ONE_MASK);
    sketch.size = 5_000;

    sketch.ensureCapacity(256);
    assertThat(sketch.size).isEqualTo(2_559);

    // The retained table's counter mass is denominated in the old maximum and outweighs the
    // observations the reset corrects against it. An underflow here stalls aging for as many
    // additions as it went negative by, restoring the old size's cadence that the retrack removes.
    sketch.increment(item);
    assertThat(sketch.size).isEqualTo(0);
    assertThat(sketch.frequency(item)).isEqualTo(1);
  }

  @Test
  void ensureCapacity_repeatedRetrack_resetReachable() {
    var sketch = makeSketch(1_024);
    for (int i = 0; i < 15; i++) {
      sketch.increment(item);
    }
    assertThat(sketch.frequency(item)).isEqualTo(15);

    // A weighted cache retracks on every addition as its entry count wobbles, so a repeated
    // clamp must not discard the aging progress and starve the periodic reset
    for (int i = 0; i < 15_000; i++) {
      sketch.increment(i);
      sketch.ensureCapacity(1_024 - (i & 1));
    }
    assertThat(sketch.frequency(item)).isLessThan(15);
  }

  @Test
  void ensureCapacity_retrack_keepsAgingProgress() {
    var sketch = makeSketch(1_024);
    for (int i = 0; i < 15; i++) {
      sketch.increment(item);
    }
    int size = sketch.size;

    // a retrack may only clamp the observed count to keep the reset reachable, never advance it
    // towards the sample threshold, else a weighted cache's per-addition retracks age the sketch
    // on nearly every increment and admission degrades towards random
    sketch.ensureCapacity(512);
    assertThat(sketch.sampleSize).isEqualTo(5_120);
    assertThat(sketch.size).isEqualTo(size);
    assertThat(sketch.frequency(item)).isEqualTo(15);
  }

  @Test
  void ensureCapacity_larger() {
    var sketch = makeSketch(512);
    int size = sketch.table.length;
    sketch.ensureCapacity(2L * size);
    assertThat(sketch.table).hasLength(2 * size);
    assertThat(sketch.sampleSize).isEqualTo(10 * 2 * size);
    assertThat(sketch.blockMask).isEqualTo(((2 * size) >> 3) - 1);
  }

  @Nested @Isolated
  final class IsolatedTest {

    @Test
    void ensureCapacity_maximum() {
      var sketch = makeSketch(512);
      int size = Integer.MAX_VALUE / 10 + 1;
      sketch.ensureCapacity(size);
      assertThat(sketch.sampleSize).isEqualTo(Integer.MAX_VALUE);
      assertThat(sketch.table).hasLength(Caffeine.ceilingPowerOfTwo(size));
      assertThat(sketch.blockMask).isEqualTo((sketch.table.length >> 3) - 1);
    }
  }

  @Test
  void ensureCapacity_exactMatch() {
    var sketch = makeSketch(512);
    int size = sketch.table.length;
    long[] table = sketch.table;
    sketch.ensureCapacity(size);
    assertThat(sketch.table).isSameInstanceAs(table);
  }

  @Test
  void spread_knownValues() {
    assertThat(FrequencySketch.spread(0)).isEqualTo(0);
    assertThat(FrequencySketch.spread(1)).isNotEqualTo(1);
    assertThat(FrequencySketch.spread(Integer.MAX_VALUE))
        .isNotEqualTo(FrequencySketch.spread(Integer.MAX_VALUE - 1));
  }

  @Test
  void incrementAt_saturated_returnsFalse() {
    var sketch = makeSketch(512);
    for (int i = 0; i < 15; i++) {
      assertThat(sketch.incrementAt(0, 0)).isTrue();
    }
    assertThat(sketch.incrementAt(0, 0)).isFalse();
  }

  @Test
  void increment_once() {
    var sketch = makeSketch(512);
    sketch.increment(item);
    assertThat(sketch.frequency(item)).isEqualTo(1);
  }

  @Test
  void increment_max() {
    var sketch = makeSketch(512);
    for (int i = 0; i < 20; i++) {
      sketch.increment(item);
    }
    assertThat(sketch.frequency(item)).isEqualTo(15);
  }

  @Test
  void increment_distinct() {
    var sketch = makeSketch(512);
    sketch.increment(item);
    sketch.increment(item + 1);
    assertThat(sketch.frequency(item)).isEqualTo(1);
    assertThat(sketch.frequency(item + 1)).isEqualTo(1);
    assertThat(sketch.frequency(item + 2)).isEqualTo(0);
  }

  @Test
  void increment_zero() {
    var sketch = makeSketch(512);
    sketch.increment(0);
    assertThat(sketch.frequency(0)).isEqualTo(1);
  }

  @Test
  void reset() {
    @Var boolean reset = false;
    var sketch = new FrequencySketch();
    sketch.ensureCapacity(64);

    for (int i = 1; i < 20 * sketch.table.length; i++) {
      sketch.increment(i);
      if (sketch.size != i) {
        reset = true;
        break;
      }
    }
    assertThat(reset).isTrue();
    assertThat(sketch.size).isAtMost(sketch.sampleSize / 2);
  }

  @Test
  void full() {
    FrequencySketch sketch = makeSketch(512);
    sketch.sampleSize = Integer.MAX_VALUE;
    for (int i = 0; i < 100_000; i++) {
      sketch.increment(i);
    }
    for (long slot : sketch.table) {
      assertThat(Long.bitCount(slot)).isEqualTo(64);
    }

    sketch.reset();
    for (long slot : sketch.table) {
      assertThat(slot).isEqualTo(FrequencySketch.RESET_MASK);
    }
  }

  @Test
  void heavyHitters() {
    FrequencySketch sketch = makeSketch(512);
    for (int i = 100; i < 100_000; i++) {
      sketch.increment((double) i);
    }
    for (int i = 0; i < 10; i += 2) {
      for (int j = 0; j < i; j++) {
        sketch.increment((double) i);
      }
    }

    // A perfect popularity count yields an array [0, 0, 2, 0, 4, 0, 6, 0, 8, 0]
    int[] popularity = new int[10];
    for (int i = 0; i < 10; i++) {
      popularity[i] = sketch.frequency((double) i);
    }
    for (int i = 0; i < popularity.length; i++) {
      if ((i == 0) || (i == 1) || (i == 3) || (i == 5) || (i == 7) || (i == 9)) {
        assertThat(popularity[i]).isAtMost(popularity[2]);
      } else if (i == 2) {
        assertThat(popularity[2]).isAtMost(popularity[4]);
      } else if (i == 4) {
        assertThat(popularity[4]).isAtMost(popularity[6]);
      } else if (i == 6) {
        assertThat(popularity[6]).isAtMost(popularity[8]);
      }
    }
  }

  private static FrequencySketch makeSketch(long maximumSize) {
    var sketch = new FrequencySketch();
    sketch.ensureCapacity(maximumSize);
    return sketch;
  }
}
