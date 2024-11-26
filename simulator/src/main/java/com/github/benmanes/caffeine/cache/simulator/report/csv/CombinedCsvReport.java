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

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;

/**
 * A utility that combines multiple CSV reports that vary by the maximum cache size into a single
 * report for comparison of a single metric (such as the hit rate).
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CombinedCsvReport implements Runnable {
  private static final String POLICY_KEY = "Policy";

  private final ImmutableSortedMap<Long, Path> inputFiles;
  private final Path outputFile;
  private final String metric;

  public CombinedCsvReport(ImmutableMap<Long, Path> inputFiles, String metric, Path outputFile) {
    this.inputFiles = ImmutableSortedMap.copyOf(inputFiles);
    this.outputFile = requireNonNull(outputFile);
    this.metric = metric.replace('_', ' ');
  }

  @Override
  public void run() {
    writeReport(tabulate());
  }

  /** Returns the results for the (policy, maximumSize) to the metric value being compared. */
  private Map<Label, String> tabulate() {
    var results = new TreeMap<Label, String>();
    inputFiles.forEach((maximumSize, path) -> {
      var records = newCsvParser().parseAllRecords(path.toFile());
      for (var record : records) {
        var label = new Label(record.getString(POLICY_KEY), maximumSize);
        results.put(label, record.getValue(metric, ""));
      }
    });
    return results;
  }

  /** Writes a combined report with the headers: policy, maximumSize, and the metric. */
  private void writeReport(Map<Label, String> table) {
    var policies = newCsvParser()
        .parseAllRecords(inputFiles.values().iterator().next().toFile()).stream()
        .map(record -> record.getString(POLICY_KEY))
        .collect(toImmutableList());
    var formatter = NumberFormat.getInstance(US);
    var headers = Stream
        .concat(Stream.of(POLICY_KEY), inputFiles.keySet().stream().map(formatter::format))
        .toArray(String[]::new);
    var writer = newWriter(headers);
    for (var policy : policies) {
      writer.addValue(POLICY_KEY, policy);
      for (Long size : inputFiles.keySet()) {
        writer.addValue(formatter.format(size), table.get(new Label(policy, size)));
      }
      writer.writeValuesToRow();
    }
    writer.close();
  }

  private static CsvParser newCsvParser() {
    var settings = new CsvParserSettings();
    settings.setHeaderExtractionEnabled(true);
    return new CsvParser(settings);
  }

  private CsvWriter newWriter(String[] headers) {
    var settings = new CsvWriterSettings();
    settings.setHeaderWritingEnabled(true);
    settings.setHeaders(headers);
    return new CsvWriter(outputFile.toFile(), settings);
  }

  private static final class Label implements Comparable<Label> {
    final String policy;
    final long size;

    Label(String policy, long size) {
      this.policy = requireNonNull(policy);
      this.size = size;
    }
    @Override
    public boolean equals(@Nullable Object o) {
      return (o instanceof Label) && (compareTo((Label) o) == 0);
    }
    @Override
    public int hashCode() {
      return Objects.hash(policy, size);
    }
    @Override
    public int compareTo(Label label) {
      int ordering = policy.compareTo(label.policy);
      return (ordering == 0) ? Long.compare(size, label.size) : ordering;
    }
  }
}
