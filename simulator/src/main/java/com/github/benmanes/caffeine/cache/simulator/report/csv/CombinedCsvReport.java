/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.writer.CsvWriter;

/**
 * A utility that combines multiple CSV reports that vary by the maximum cache size into a single
 * report for comparison of a single metric (such as the hit rate).
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public record CombinedCsvReport(ImmutableMap<Long, Path> inputFiles,
    String metric, Path outputFile) implements Runnable {
  private static final String POLICY_KEY = "Policy";

  @SuppressWarnings("Var")
  public CombinedCsvReport {
    inputFiles = ImmutableSortedMap.copyOf(inputFiles);
    metric = metric.replace('_', ' ');
    requireNonNull(outputFile);
  }

  @Override
  public void run() {
    writeReport(tabulate());
  }

  /** Returns the results for the (policy, maximumSize) to the metric value being compared. */
  private Map<Label, String> tabulate() {
    var results = new TreeMap<Label, String>();
    inputFiles.forEach((maximumSize, path) -> {
      try (var reader = CsvReader.builder().ofNamedCsvRecord(path)) {
        for (var record : reader) {
          var label = new Label(record.getField(POLICY_KEY), maximumSize);
          results.put(label, record.findField(metric).orElse(""));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    return results;
  }

  /** Writes a combined report with the headers: policy, maximumSize, and the metric. */
  private void writeReport(Map<Label, String> table) {
    var formatter = NumberFormat.getInstance(US);
    var headers = Stream
        .concat(Stream.of(POLICY_KEY), inputFiles.keySet().stream().map(formatter::format))
        .collect(toImmutableList());
    try (var writer = CsvWriter.builder().build(outputFile)) {
      writer.writeRecord(headers);
      for (var policy : policies()) {
        var values = new ArrayList<String>();
        values.add(policy);
        for (long size : inputFiles.keySet()) {
          values.add(table.get(new Label(policy, size)));
        }
        writer.writeRecord(values);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ImmutableList<String> policies() {
    Path input = inputFiles.values().iterator().next();
    try (var reader = CsvReader.builder().ofNamedCsvRecord(input)) {
      return reader.stream()
          .map(record -> record.getField(POLICY_KEY))
          .collect(toImmutableList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private record Label(String policy, long size) implements Comparable<Label> {
    Label {
      requireNonNull(policy);
    }
    @Override public int compareTo(Label label) {
      int ordering = policy.compareTo(label.policy);
      return (ordering == 0) ? Long.compare(size, label.size) : ordering;
    }
  }
}
