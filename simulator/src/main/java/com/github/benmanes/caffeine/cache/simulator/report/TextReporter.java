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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.US;
import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.PENALTIES;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * A skeletal plain text implementation applicable for printing to the console or a file.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TextReporter implements Reporter {
  private final String[] HEADERS;

  private final List<PolicyStats> results;
  private final BasicSettings settings;
  private final String timeUnit;
  private final Set<Characteristic> characteristics;

  public TextReporter(Config config) {
    settings = new BasicSettings(config);
    results = new ArrayList<>();
    timeUnit = settings.report().timeUnit();
    characteristics = settings.report().characteristics();
    String[] basicHeaders = {
            "Policy", "Hit rate", "Hits", "Misses", "Requests",
            "Evictions", "Admit rate", "Steps", "Time"};
    String[] penaltyHeaders = {"Avg. Hit Penalty","Avg. Miss Penalty","Avg. Total Penalty","Avg. Miss Penalty (AFS)","Avg. Total Penalty (AFS)"};
    String[] empty = {};
    HEADERS = mergeStringData(basicHeaders,characteristics.contains(PENALTIES) ? penaltyHeaders : empty);
  }

  /** Returns the column headers. */
  protected String[] headers() {
    return HEADERS.clone();
  }

  /** Returns the column headers. */
  protected Set<Characteristic> characteristics() {
    return characteristics;
  }

  /** Adds the result of a policy simulation. */
  @Override
  public void add(PolicyStats policyStats) {
    results.add(policyStats);
  }

  /** Writes the report to the output destination. */
  @Override
  public void print() throws IOException {
    results.sort(comparator());
    String report = assemble(results);
    String output = settings.report().output();
    if (output.equalsIgnoreCase("console")) {
      System.out.println(report);
    } else {
      Files.write(Paths.get(output), report.getBytes(UTF_8));
    }
  }

  /** Assembles an aggregated report. */
  protected abstract String assemble(List<PolicyStats> results);

  /** Returns a comparator that sorts by the specified column. */
  private Comparator<PolicyStats> comparator() {
    Comparator<PolicyStats> comparator = makeComparator();
    return settings.report().ascending() ? comparator : comparator.reversed();
  }


  String getTimeUnit(){
    return timeUnit;
  }


  private Comparator<PolicyStats> makeComparator() {
    switch (settings.report().sortBy().toLowerCase(US)) {
      case "policy":
        return Comparator.comparing(PolicyStats::name);
      case "hit rate":
        return Comparator.comparingDouble(PolicyStats::hitRate);
      case "hits":
        return Comparator.comparingLong(PolicyStats::hitCount);
      case "misses":
        return Comparator.comparingLong(PolicyStats::missCount);
      case "evictions":
        return Comparator.comparingLong(PolicyStats::evictionCount);
      case "admit rate":
        return Comparator.comparingLong(PolicyStats::admissionCount);
      case "steps":
        return Comparator.comparingLong(PolicyStats::operationCount);
      case "time":
        return Comparator.comparingLong(stats -> stats.stopwatch().elapsed(TimeUnit.NANOSECONDS));
      default:
        throw new IllegalArgumentException("Unknown sort order: " + settings.report().sortBy());
    }
  }

  protected static String[] mergeStringData(String[] ...arrays) {
    return Stream.of(arrays)
            .flatMap(Stream::of)
            .toArray(String[]::new);
  }
}
