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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multiset;

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

  /** Returns the policy order and metric values, validating the inputs before writing the report. */
  private Table tabulate() {
    var results = new TreeMap<Label, String>();
    var columns = new LinkedHashSet<String>();
    var policies = new LinkedHashSet<String>();
    var duplicatesByInput = new ArrayList<ImmutableList<String>>();
    inputFiles.forEach((maximumSize, path) -> {
      try (var reader = CsvReader.builder().ofNamedCsvRecord(path)) {
        var names = ImmutableList.<String>builder();
        for (var record : reader) {
          var column = resolveMetric(record.getHeader());
          var label = new Label(record.getField(POLICY_KEY), maximumSize);
          results.put(label, column.map(record::getField).orElse(""));
          columns.addAll(record.getHeader());
          names.add(label.policy());
        }
        var policiesInFile = names.build();
        var duplicates = HashMultiset.create(policiesInFile).entrySet().stream()
            .filter(entry -> entry.getCount() > 1)
            .map(Multiset.Entry::getElement)
            .collect(toImmutableList());
        duplicatesByInput.add(duplicates);
        policies.addAll(policiesInFile);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
    checkArgument(columns.stream().anyMatch(column -> column.equalsIgnoreCase(metric)),
        "Metric '%s' not found; available: %s", metric, columns.stream()
            .filter(column -> !column.equals(POLICY_KEY)).collect(toImmutableList()));
    for (var duplicates : duplicatesByInput) {
      checkState(duplicates.isEmpty(),
          "Policies share a display name so their rows would collapse: %s", duplicates);
    }
    return new Table(ImmutableList.copyOf(policies), ImmutableMap.copyOf(results));
  }

  /** Returns the metric's column name ignoring case, or empty if this report omitted it. */
  private Optional<String> resolveMetric(Collection<String> header) {
    return header.stream().filter(column -> column.equalsIgnoreCase(metric)).findFirst();
  }

  /** Writes a combined report with the headers: policy, maximumSize, and the metric. */
  private void writeReport(Table table) {
    var formatter = NumberFormat.getInstance(US);
    var headers = Stream
        .concat(Stream.of(POLICY_KEY), inputFiles.keySet().stream().map(formatter::format))
        .collect(toImmutableList());
    try (var writer = CsvWriter.builder().build(outputFile)) {
      writer.writeRecord(headers);
      for (var policy : table.policies()) {
        var values = new ArrayList<String>();
        values.add(policy);
        for (long size : inputFiles.keySet()) {
          values.add(table.values().getOrDefault(new Label(policy, size), ""));
        }
        writer.writeRecord(values);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The first-seen policy order and metric values across all input reports. */
  private record Table(ImmutableList<String> policies, ImmutableMap<Label, String> values) {}

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
