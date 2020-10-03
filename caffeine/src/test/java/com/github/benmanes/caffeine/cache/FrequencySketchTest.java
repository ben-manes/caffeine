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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

import java.util.HashSet;
import java.util.Set;
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
    FrequencySketch<Integer> sketch = new FrequencySketch<>();
    assertThat(sketch.table, is(nullValue()));
  }

  @Test(dataProvider = "sketch", expectedExceptions = IllegalArgumentException.class)
  public void ensureCapacity_negative(FrequencySketch<Integer> sketch) {
    sketch.ensureCapacity(-1);
  }

  @Test(dataProvider = "sketch")
  public void ensureCapacity_smaller(FrequencySketch<Integer> sketch) {
    int size = sketch.table.length;
    sketch.ensureCapacity(size / 2);
    assertThat(sketch.table.length, is(size));
    assertThat(sketch.tableMask, is(size - 1));
    assertThat(sketch.sampleSize, is(10 * size));
  }

  @Test(dataProvider = "sketch")
  public void ensureCapacity_larger(FrequencySketch<Integer> sketch) {
    int size = sketch.table.length;
    sketch.ensureCapacity(2 * (long) size);
    assertThat(sketch.table.length, is(2 * size));
    assertThat(sketch.tableMask, is(2 * size - 1));
    assertThat(sketch.sampleSize, is(10 * 2 * size));
  }

  @Test(dataProvider = "sketch")
  public void increment_once(FrequencySketch<Integer> sketch) {
    sketch.increment(item);
    assertThat(sketch.frequency(item), is(1));
  }

  @Test(dataProvider = "sketch")
  public void increment_max(FrequencySketch<Integer> sketch) {
    for (int i = 0; i < 20; i++) {
      sketch.increment(item);
    }
    assertThat(sketch.frequency(item), is(15));
  }

  @Test(dataProvider = "sketch")
  public void increment_distinct(FrequencySketch<Integer> sketch) {
    sketch.increment(item);
    sketch.increment(item + 1);
    assertThat(sketch.frequency(item), is(1));
    assertThat(sketch.frequency(item + 1), is(1));
    assertThat(sketch.frequency(item + 2), is(0));
  }

  @Test(dataProvider = "sketch")
  public void indexOf_aroundZero(FrequencySketch<Integer> sketch) {
    Set<Integer> indexes = new HashSet<>(16);
    int[] hashes = { -1, 0, 1 };
    for (int hash : hashes) {
      for (int i = 0; i < 4; i++) {
        indexes.add(sketch.indexOf(hash, i));
      }
    }
    assertThat(indexes, hasSize(4 * hashes.length));
  }

  @Test
  public void reset() {
    boolean reset = false;
    FrequencySketch<Integer> sketch = new FrequencySketch<>();
    sketch.ensureCapacity(64);

    for (int i = 1; i < 20 * sketch.table.length; i++) {
      sketch.increment(i);
      if (sketch.size != i) {
        reset = true;
        break;
      }
    }
    assertThat(reset, is(true));
    assertThat(sketch.size, lessThanOrEqualTo(sketch.sampleSize / 2));
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
        assertThat(popularity[i], lessThanOrEqualTo(popularity[2]));
      } else if (i == 2) {
        assertThat(popularity[2], lessThanOrEqualTo(popularity[4]));
      } else if (i == 4) {
        assertThat(popularity[4], lessThanOrEqualTo(popularity[6]));
      } else if (i == 6) {
        assertThat(popularity[6], lessThanOrEqualTo(popularity[8]));
      }
    }
  }

  @DataProvider(name = "sketch")
  public Object[][] providesSketch() {
    return new Object[][] {{ makeSketch(512) }};
  }

  private static <E> FrequencySketch<E> makeSketch(long maximumSize) {
    FrequencySketch<E> sketch = new FrequencySketch<>();
    sketch.ensureCapacity(maximumSize);
    return sketch;
  }
}
