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
package com.github.benmanes.caffeine.cache.simulator.report.csv;

import static java.util.Locale.US;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.report.Metrics;
import com.github.benmanes.caffeine.cache.simulator.report.TextReporter;
import com.google.common.base.Stopwatch;
import com.typesafe.config.Config;

import de.siegmar.fastcsv.writer.CsvWriter;

/**
 * A plain text report that prints comma-separated values.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CsvReporter extends TextReporter {

  public CsvReporter(Config config, Set<Characteristic> characteristics) {
    super(config, characteristics);
  }

  @Override
  protected String assemble(Set<String> headers, List<PolicyStats> results) {
    try (var output = new StringWriter();
         var writer = CsvWriter.builder().build(output)) {
      writer.writeRecord(headers);
      for (PolicyStats policyStats : results) {
        writer.writeRecord(headers.stream()
            .map(policyStats.metrics()::get)
            .map(metrics()::format)
            .toList());
      }
      return output.toString();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  protected Metrics metrics() {
    return Metrics.builder()
        .percentFormatter(value -> String.format(US, "%.2f", 100 * value))
        .doubleFormatter(value -> String.format(US, "%.2f", value))
        .longFormatter(Long::toString)
        .objectFormatter(object -> {
          return (object instanceof Stopwatch stopwatch)
              ? Long.toString(stopwatch.elapsed(TimeUnit.MILLISECONDS))
              : object.toString();
        }).build();
  }
}
