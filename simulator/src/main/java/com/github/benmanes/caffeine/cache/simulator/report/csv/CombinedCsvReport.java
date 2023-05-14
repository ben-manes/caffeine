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
import java.util.SortedMap;
import java.util.stream.Stream;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
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
  private final SortedMap<Long, Path> inputFiles;
  private final Path outputFile;
  private final String metric;

  public CombinedCsvReport(SortedMap<Long, Path> inputFiles, String metric, Path outputFile) {
    this.inputFiles = requireNonNull(inputFiles);
    this.outputFile = requireNonNull(outputFile);
    this.metric = metric.replace('_', ' ');
  }

  @Override
  public void run() {
    writeReport(tabulate());
  }

  /** Returns the combined results for the policy, maximum size, and the metric being compared. */
  private Table</* policy */ String, /* size */ Long, /* metric */ String> tabulate() {
    Table<String, Long, String> results = HashBasedTable.create();
    inputFiles.forEach((maximumSize, path) -> {
      var records = newCsvParser().parseAllRecords(path.toFile());
      for (var record : records) {
        results.put(record.getString("Policy"), maximumSize, record.getValue(metric, ""));
      }
    });
    return results;
  }

  /** Writes a combined report with the headers: policy, maximumSize, and the metric. */
  private void writeReport(Table<String, Long, String> table) {
    var policies = newCsvParser()
        .parseAllRecords(inputFiles.values().iterator().next().toFile()).stream()
        .map(record -> record.getString("Policy"))
        .collect(toImmutableList());
    var formatter = NumberFormat.getInstance(US);
    var headers = Stream
        .concat(Stream.of("Policy"), inputFiles.keySet().stream().map(formatter::format))
        .toArray(String[]::new);
    var writer = newWriter(headers);
    for (var policy : policies) {
      writer.addValue("Policy", policy);
      for (Long size : inputFiles.keySet()) {
        writer.addValue(formatter.format(size), table.get(policy, size));
      }
      writer.writeValuesToRow();
    }
    writer.close();
  }

  private CsvParser newCsvParser() {
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
}
