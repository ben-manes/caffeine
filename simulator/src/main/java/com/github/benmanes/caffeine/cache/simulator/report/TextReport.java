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
package com.github.benmanes.caffeine.cache.simulator.report;

import static java.util.Objects.requireNonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.jooq.lambda.Seq;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.jakewharton.fliptables.FlipTable;

/**
 * A plain text report applicable for printing to the console or a file.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TextReport {
  private static final String[] HEADERS = { "Policy", "Hit rate", "Requests", "Evictions", "Time"};

  private final Set<PolicyStats> results;
  private final BasicSettings settings;

  public TextReport(BasicSettings settings) {
    this.settings = requireNonNull(settings);
    results = new TreeSet<>(settings.report().ascending() ? comparator() : comparator().reversed());
  }

  /** Adds the result of a policy simulation. */
  public void add(PolicyStats policyStats) {
    results.add(policyStats);
  }

  /** Writes the report to the output destination. */
  public void print() throws IOException {
    String output = settings.report().output();
    String results = assemble();
    if (output.equalsIgnoreCase("console")) {
      System.out.println(results);
    } else {
      File file = Paths.get(output).toFile();
      Files.write(results, file, Charsets.UTF_8);
    }
  }

  /** Assembles an aggregated report. */
  private String assemble() {
    String[][] data = new String[results.size()][HEADERS.length];
    Seq.seq(results).zipWithIndex().forEach(statsAndIndex -> {
      PolicyStats policyStats = statsAndIndex.v1;
      int index = statsAndIndex.v2.intValue();
      data[index] = new String[] {
          policyStats.name(),
          String.format("%.2f %%", 100 * policyStats.hitRate()),
          String.format("%,d", policyStats.requestCount()),
          String.format("%,d", policyStats.evictionCount()),
          policyStats.stopwatch().toString()
      };
    });
    return FlipTable.of(HEADERS, data);
  }

  /** Returns a comparator that sorts by the specified column. */
  private Comparator<PolicyStats> comparator() {
    switch (settings.report().sortBy().toLowerCase()) {
      case "policy":
        return (first, second) -> first.name().compareTo(second.name());
      case "hit rate":
        return (first, second) -> Double.compare(first.hitRate(), second.hitRate());
      case "requests":
        return (first, second) -> Long.compare(first.requestCount(), second.requestCount());
      case "evictions":
        return (first, second) -> Long.compare(first.evictionCount(), second.evictionCount());
      case "time":
        return (first, second) -> Long.compare(first.stopwatch().elapsed(TimeUnit.NANOSECONDS),
            second.stopwatch().elapsed(TimeUnit.NANOSECONDS));
      default:
        throw new IllegalArgumentException("Unknown sort order: " + settings.report().sortBy());
    }
  }
}
