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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

/**
 * A skeletal plain text implementation applicable for printing to the console or a file.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TextReporter implements Reporter {
  private final Set<Characteristic> characteristics;
  private final List<PolicyStats> results;
  private final BasicSettings settings;

  private ImmutableSet<String> headers;
  private Metrics metrics;

  protected TextReporter(Config config, Set<Characteristic> characteristics) {
    this.characteristics = requireNonNull(characteristics);
    this.settings = new BasicSettings(config);
    this.results = new ArrayList<>();
  }

  @Override
  public void add(PolicyStats policyStats) {
    results.add(policyStats);
  }

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

  @Override
  public Collection<PolicyStats> stats() {
    return results;
  }

  /** Returns the column headers. */
  protected Set<String> headers() {
    if (headers == null) {
      Set<String> all = results.stream()
            .flatMap(policyStats -> policyStats.metrics().keySet().stream())
            .collect(toImmutableSet());
      Set<String> used = results.stream()
          .flatMap(policyStats -> policyStats.metrics().values().stream())
          .filter(metric -> metric.characteristics().isEmpty()
              || metric.characteristics().stream().anyMatch(characteristics::contains))
          .filter(metric -> metric.required() || !metrics().format(metric).isEmpty())
          .map(Metric::name)
          .collect(toImmutableSet());
      headers = ImmutableSet.copyOf(Sets.intersection(all, used));
    }
    return headers;
  }

  /** Returns the configuration for how to work with metrics. */
  protected Metrics metrics() {
    return (metrics == null) ? (metrics = newMetrics()) : metrics;
  }

  /** Returns a new configuration for how to work with metrics. */
  protected abstract Metrics newMetrics();

  /** Assembles an aggregated report. */
  protected abstract String assemble(List<PolicyStats> results);

  /** Returns a comparator that sorts by the specified column. */
  private Comparator<PolicyStats> comparator() {
    String sortBy = results.stream()
        .flatMap(policyStats -> policyStats.metrics().keySet().stream())
        .filter(header -> header.toLowerCase(US).equals(settings.report().sortBy().toLowerCase(US)))
        .findAny().orElseThrow(() -> new IllegalArgumentException(
            "Unknown sort order: " + settings.report().sortBy()));
    Comparator<PolicyStats> comparator = metrics().comparator(sortBy);
    return settings.report().ascending() ? comparator : comparator.reversed();
  }
}
