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

import static com.google.common.truth.Truth.assertThat;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import com.google.common.collect.Range;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class FrequencySketchFuzzer {
  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments
  static final Range<Integer> FREQUENCY_RANGE = Range.closed(0, 15);

  final FrequencySketch sketch;

  FrequencySketchFuzzer() {
    sketch = new FrequencySketch();
    sketch.ensureCapacity(1024 * 1024);
  }

  @FuzzTest(maxDuration = "5m")
  void sketch(@NotNull Integer item) {
    sketch.increment(item);
    assertThat(sketch.frequency(item)).isIn(FREQUENCY_RANGE);
  }
}
