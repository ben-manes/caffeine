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
import static org.junit.Assert.assertThrows;

import java.util.concurrent.ThreadLocalRandom;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class FrequencySketchTest {
  final Integer item = ThreadLocalRandom.current().nextInt();

  @Test
  public void construct() {
    var sketch = new FrequencySketch<Integer>();
    assertThat(sketch.table).isNull();
    assertThat(sketch.isNotInitialized()).isTrue();

    sketch.increment(item);
    assertThat(sketch.frequency(item)).isEqualTo(0);
  }

  @Test(dataProvider = "sketch")
  public void ensureCapacity_negative(FrequencySketch<Integer> sketch) {
    assertThrows(IllegalArgumentException.class, () -> sketch.ensureCapacity(-1));
  }

  @Test(dataProvider = "sketch")
  public void ensureCapacity_smaller(FrequencySketch<Integer> sketch) {
    int size = sketch.table.length;
    sketch.ensureCapacity(size / 2);
    assertThat(sketch.table).hasLength(size);
    assertThat(sketch.sampleSize).isEqualTo(10 * size);
    assertThat(sketch.blockMask).isEqualTo((size >> 3) - 1);
  }

  @Test(dataProvider = "sketch")
  public void ensureCapacity_larger(FrequencySketch<Integer> sketch) {
    int size = sketch.table.length;
    sketch.ensureCapacity(2 * size);
    assertThat(sketch.table).hasLength(2 * size);
    assertThat(sketch.sampleSize).isEqualTo(10 * 2 * size);
    assertThat(sketch.blockMask).isEqualTo(((2 * size) >> 3) - 1);
  }

  @Test(dataProvider = "sketch", groups = "isolated")
  public void ensureCapacity_maximum(FrequencySketch<Integer> sketch) {
    int size = Integer.MAX_VALUE / 10 + 1;
    sketch.ensureCapacity(size);
    assertThat(sketch.sampleSize).isEqualTo(Integer.MAX_VALUE);
    assertThat(sketch.table).hasLength(Caffeine.ceilingPowerOfTwo(size));
    assertThat(sketch.blockMask).isEqualTo((sketch.table.length >> 3) - 1);
  }

  @Test(dataProvider = "sketch")
  public void increment_once(FrequencySketch<Integer> sketch) {
    sketch.increment(item);
    assertThat(sketch.frequency(item)).isEqualTo(1);
  }

  @Test(dataProvider = "sketch")
  public void increment_max(FrequencySketch<Integer> sketch) {
    for (int i = 0; i < 20; i++) {
      sketch.increment(item);
    }
    assertThat(sketch.frequency(item)).isEqualTo(15);
  }

  @Test(dataProvider = "sketch")
  public void increment_distinct(FrequencySketch<Integer> sketch) {
    sketch.increment(item);
    sketch.increment(item + 1);
    assertThat(sketch.frequency(item)).isEqualTo(1);
    assertThat(sketch.frequency(item + 1)).isEqualTo(1);
    assertThat(sketch.frequency(item + 2)).isEqualTo(0);
  }

  @Test(dataProvider = "sketch")
  public void increment_zero(FrequencySketch<Integer> sketch) {
    sketch.increment(0);
    assertThat(sketch.frequency(0)).isEqualTo(1);
  }

  @Test
  public void reset() {
    boolean reset = false;
    var sketch = new FrequencySketch<Integer>();
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
  public void full() {
    FrequencySketch<Integer> sketch = makeSketch(512);
    sketch.sampleSize = Integer.MAX_VALUE;
    for (int i = 0; i < 100_000; i++) {
      sketch.increment(i);
    }
    for (long item : sketch.table) {
      assertThat(Long.bitCount(item)).isEqualTo(64);
    }

    sketch.reset();
    for (long item : sketch.table) {
      assertThat(item).isEqualTo(FrequencySketch.RESET_MASK);
    }
  }

  @Test
  public void heavyHitters() {
    FrequencySketch<Double> sketch = makeSketch(512);
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

  @DataProvider(name = "sketch")
  public Object[][] providesSketch() {
    return new Object[][] {{ makeSketch(512) }};
  }

  private static <E> FrequencySketch<E> makeSketch(long maximumSize) {
    var sketch = new FrequencySketch<E>();
    sketch.ensureCapacity(maximumSize);
    return sketch;
  }
}
