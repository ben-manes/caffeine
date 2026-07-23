/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.report.csv;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.jfree.data.category.DefaultCategoryDataset;
import org.junit.jupiter.api.Test;

/**
 * The chart's value axis is fixed to the data's range with a margin. Degenerate data must not
 * produce an empty range: equal values still need a window to render, and data with no numeric
 * points fails loudly instead of yielding an inverted range.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class PlotCsvTest {

  @Test
  void equalValuesYieldANonEmptyRange() {
    var dataset = new DefaultCategoryDataset();
    dataset.addValue(50.0, "Lru", "256");
    dataset.addValue(50.0, "Lru", "512");

    var range = PlotCsv.calculateRange(dataset);
    assertThat(range.getLength()).isGreaterThan(0.0);
    assertThat(range.contains(50.0)).isTrue();
  }

  @Test
  void noNumericDataFailsLoudly() {
    var dataset = new DefaultCategoryDataset();
    dataset.addValue(null, "Lru", "256");

    assertThrows(IllegalStateException.class, () -> PlotCsv.calculateRange(dataset));
  }
}
