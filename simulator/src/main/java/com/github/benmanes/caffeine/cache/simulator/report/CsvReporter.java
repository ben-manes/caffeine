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

import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.Characteristics;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;
import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;

/**
 * A plain text report that prints comma-separated values.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CsvReporter extends TextReporter {

  public CsvReporter(Config config) {
    super(config);
  }

  @Override
  protected String assemble(List<PolicyStats> results) {
    StringWriter output = new StringWriter();
    CsvWriter writer = new CsvWriter(output, new CsvWriterSettings());
    writer.writeHeaders(headers());
    boolean mpFlag = hasMissPenalty();
    boolean hpFlag = hasHitPenalty();
    for (PolicyStats policyStats : results) {
      Object[] mpData = mpFlag ? new Object[] {
              (policyStats.missCount() == 0) ? null : String.format("%.2f ms", policyStats.avgMissLatency()),
              String.format("%.2f ms", policyStats.avgTotalLatency()),
              String.format("%.2f ms", policyStats.avgMissLatencyAFS()),
              String.format("%.2f ms", policyStats.avgTotalLatencyAFS())
      } : new Object[] {};
      Object[] hpData = hpFlag ? new Object[] { policyStats.hitCount() == 0 ? null : String.format("%.2f ms", policyStats.avgHitLatency())} : new Object[] {};
      Object[] mainData = {
              policyStats.name(),
              String.format("%.2f", 100 * policyStats.hitRate()),
              policyStats.hitCount(),
              policyStats.missCount(),
              policyStats.requestCount(),
              policyStats.evictionCount(),
              String.format("%.2f", 100 * policyStats.admissionRate()),
              (policyStats.operationCount() == 0) ? null : policyStats.operationCount(),
              policyStats.stopwatch().elapsed(TimeUnit.MILLISECONDS)
      };
      Object[] data = mergeData(mainData,hpData,mpData);
      writer.writeRow(data);
    }
    writer.close();
    return output.toString();
  }

  private static Object[] mergeData(Object[] ...arrays) {
    return Stream.of(arrays)
            .flatMap(Stream::of)
            .toArray(Object[]::new);
  }
}
