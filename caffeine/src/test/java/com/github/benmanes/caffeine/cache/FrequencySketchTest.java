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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import java.util.concurrent.ThreadLocalRandom;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class FrequencySketchTest {
  final Integer item = ThreadLocalRandom.current().nextInt();

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
    sketch.ensureCapacity(size * 2);
    assertThat(sketch.table.length, is(size * 2));
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

  @Test
  public void reset() {
    boolean reset = false;
    FrequencySketch<Integer> sketch = new FrequencySketch<>(64);
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

  @DataProvider(name = "sketch")
  public Object[][] providesSketch() {
    return new Object[][] {{ new FrequencySketch<>(512) }};
  }
}
