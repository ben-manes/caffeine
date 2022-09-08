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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats.Metric;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.typesafe.config.Config;

/**
 * A skeletal plain text implementation applicable for printing to the console or a file.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TextReporter implements Reporter {
  private final Set<Characteristic> characteristics;
  private final BasicSettings settings;

  protected TextReporter(Config config, Set<Characteristic> characteristics) {
    this.characteristics = requireNonNull(characteristics);
    this.settings = new BasicSettings(config);
  }

  @Override
  public void print(List<PolicyStats> results) {
    var sortedStats = getSortedStats(results);
    var headers = getHeaders(sortedStats);

    String report = assemble(headers, sortedStats);
    String output = settings.report().output();
    if (output.equalsIgnoreCase("console")) {
      System.out.println(report);
      return;
    }
    try {
      Path path = Path.of(output);
      Files.createDirectories(path.getParent());
      Files.write(path, report.getBytes(UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private ImmutableList<PolicyStats> getSortedStats(Collection<PolicyStats> results) {
    String sortBy = results.stream()
        .flatMap(policyStats -> policyStats.metrics().keySet().stream())
        .filter(header -> header.toLowerCase(US).equals(settings.report().sortBy().toLowerCase(US)))
        .findAny().orElseThrow(() -> new IllegalArgumentException(
            "Unknown sort order: " + settings.report().sortBy()));
    return ImmutableSortedSet.copyOf(comparator(sortBy), results).asList();
  }

  /** Returns the column headers in declaration order. */
  private ImmutableSet<String> getHeaders(Collection<PolicyStats> results) {
    var columns = results.stream()
        .flatMap(policyStats -> policyStats.metrics().values().stream())
        .filter(metric -> metric.characteristics().isEmpty()
            || metric.characteristics().stream().anyMatch(characteristics::contains))
        .filter(metric -> metric.required() || !metrics().format(metric).isEmpty())
        .map(Metric::name)
        .collect(toImmutableSet());
    return results.stream()
        .flatMap(policyStats -> policyStats.metrics().keySet().stream())
        .filter(columns::contains)
        .collect(toImmutableSet());
  }

  /** Returns the configuration for how to work with metrics. */
  protected abstract Metrics metrics();

  /** Assembles an aggregated report. */
  protected abstract String assemble(Set<String> headers, List<PolicyStats> results);

  /** Returns a comparator that sorts by the specified column. */
  private Comparator<PolicyStats> comparator(String sortBy) {
    Comparator<PolicyStats> comparator = metrics().comparator(sortBy);
    return settings.report().ascending() ? comparator : comparator.reversed();
  }
}
