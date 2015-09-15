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
package com.github.benmanes.caffeine.cache.simulator.admission.sketch;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.concurrent.ThreadLocalRandom;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class FrequencySketchTest {
  final Integer item = ThreadLocalRandom.current().nextInt();
  FrequencySketch<Integer> sketch;

  @BeforeMethod
  public void before() {
    sketch = new FrequencySketch<>(512);
  }

  @Test
  public void increment_once() {
    sketch.increment(item);
    int count = sketch.frequency(item);
    assertThat(count, is(1));
  }

  @Test
  public void increment_max() {
    for (int i = 0; i < 20; i++) {
      sketch.increment(item);
    }
    int count = sketch.frequency(item);
    assertThat(count, is(15));
  }
}
