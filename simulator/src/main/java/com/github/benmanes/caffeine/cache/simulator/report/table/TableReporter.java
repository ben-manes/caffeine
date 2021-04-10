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
package com.github.benmanes.caffeine.cache.simulator.report.table;

import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.report.Metrics;
import com.github.benmanes.caffeine.cache.simulator.report.TextReporter;
import com.jakewharton.fliptables.FlipTable;
import com.typesafe.config.Config;

/**
 * A plain text report that pretty-prints to a table.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TableReporter extends TextReporter {

  public TableReporter(Config config, Set<Characteristic> characteristics) {
    super(config, characteristics);
  }

  @Override
  protected String assemble(List<PolicyStats> results) {
    String[][] data = new String[results.size()][headers().size()];
    for (int i = 0; i < results.size(); i++) {
      PolicyStats policyStats = results.get(i);
      data[i] = headers().stream()
          .map(policyStats.metrics()::get)
          .map(metrics()::format)
          .toArray(String[]::new);
    }
    return FlipTable.of(headers().toArray(new String[0]), data);
  }

  @Override
  protected Metrics newMetrics() {
    return Metrics.builder()
        .percentFormatter(value -> String.format("%.2f %%", 100 * value))
        .doubleFormatter(value -> String.format("%.2f", value))
        .longFormatter(value -> String.format("%,d", value))
        .build();
  }
}
