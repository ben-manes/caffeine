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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.ImmutableMap;

/**
 * The metric is a human-typed argument, so it is matched against the column headers ignoring case,
 * and an absent metric fails loudly rather than silently emitting an all-empty report (which the
 * chart step later cannot render).
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CombinedCsvReportTest {

  @Test
  void resolvesMetricIgnoringCase(@TempDir Path dir) throws IOException {
    Path input = Files.writeString(dir.resolve("in.csv"),
        "Policy,Hit Rate,Hit Penalty\nLru,50.0,1.0\nFifo,40.0,2.0\n");
    Path output = dir.resolve("out.csv");

    new CombinedCsvReport(ImmutableMap.of(512L, input), "hit rate", output).run();

    String report = Files.readString(output);
    assertThat(report).contains("Lru,50.0");
    assertThat(report).contains("Fifo,40.0");
  }

  @Test
  void failsOnUnknownMetric(@TempDir Path dir) throws IOException {
    Path input = Files.writeString(dir.resolve("in.csv"), "Policy,Hit Rate\nLru,50.0\n");
    var report = new CombinedCsvReport(ImmutableMap.of(512L, input), "Bogus", dir.resolve("out.csv"));

    var error = assertThrows(IllegalArgumentException.class, report::run);
    assertThat(error).hasMessageThat().contains("Bogus");
    assertThat(error).hasMessageThat().contains("Hit Rate");
  }
}
