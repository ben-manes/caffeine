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

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.jakewharton.fliptables.FlipTable;

/**
 * A plain text report applicable for printing to the console or a file.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TextReport {
  private final List<PolicyStats> results;

  public TextReport() {
    results = new ArrayList<>();
  }

  /** Adds the result of a policy simulation. */
  public void add(PolicyStats policyStats) {
    results.add(policyStats);
  }

  /** Writes an aggregated report. */
  public void writeTo(PrintStream ps) {
    results.sort((first, second) -> first.name().compareTo(second.name()));

    String[] headers = { "Policy", "Hit rate", "Requests", "Evictions", "Time"};
    String[][] data = new String[results.size()][headers.length];
    for (int i = 0; i < results.size(); i++) {
      PolicyStats policyStats = results.get(i);
      data[i] = new String[] {
          policyStats.name(),
          String.format("%.2f %%", 100 * policyStats.hitRate()),
          String.format("%,d", policyStats.requestCount()),
          String.format("%,d", policyStats.evictionCount()),
          policyStats.stopwatch().toString()
      };
    }
    ps.append(FlipTable.of(headers, data));
  }
}
