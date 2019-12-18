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

import java.util.List;

import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.jakewharton.fliptables.FlipTable;
import com.typesafe.config.Config;

/**
 * A plain text report that pretty-prints to a table.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TableReporter extends TextReporter {

  public TableReporter(Config config) {
    super(config);
  }

  /** Assembles an aggregated report. */
  @Override
  @SuppressWarnings("PMD.AvoidDuplicateLiterals")
  protected String assemble(List<PolicyStats> results) {
    String[][] data = new String[results.size()][headers().length];
    for (int i = 0; i < results.size(); i++) {
      PolicyStats policyStats = results.get(i);
      data[i] = new String[] {
          policyStats.name(),
          String.format("%.2f %%", 100 * policyStats.hitRate()),
          String.format("%,d", policyStats.hitCount()),
          String.format("%,d", policyStats.missCount()),
          String.format("%,d", policyStats.requestCount()),
          String.format("%,d", policyStats.evictionCount()),
          String.format("%.2f %%", 100 * policyStats.admissionRate()),
          String.format("%,d", policyStats.requestsWeight()),
          String.format("%.2f %%", 100 * policyStats.weightedHitRate()),
          String.format("%.2f", policyStats.averageMissPenalty()),
          String.format("%.2f", policyStats.avergePenalty()),
          steps(policyStats),
          policyStats.stopwatch().toString()
      };
    }
    return FlipTable.of(headers(), data);
  }

  private static String steps(PolicyStats policyStats) {
    long operations = policyStats.operationCount();
    long complexity = (long) (100 * policyStats.complexity());
    return (operations == 0) ? "?" : String.format("%,d (%,d %%)", operations, complexity);
  }
}
