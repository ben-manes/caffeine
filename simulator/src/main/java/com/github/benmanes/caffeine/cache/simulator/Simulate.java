/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator;

import static java.util.Locale.US;

import java.nio.file.Path;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Stack;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.simulator.report.csv.CombinedCsvReport;
import com.github.benmanes.caffeine.cache.simulator.report.csv.PlotCsv;
import com.github.benmanes.caffeine.cache.simulator.report.csv.PlotCsv.ChartStyle;
import com.google.common.base.Stopwatch;
import com.typesafe.config.ConfigFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.IParameterPreprocessor;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;

/**
 * A command that runs multiple simulations, writes the result to a csv file, and renders a chart.
 * An underscore may be used as a numeric separator and the default configuration may be overridden
 * by using system properties.
 * <p>
 * <pre>{@code
 *   ./gradlew simulator:simulate -q \
 *     -Dcaffeine.simulator.files.paths.0="lirs:gli.trace.gz" \
 *     -Dcaffeine.simulator.policies.0=product.Caffeine \
 *     -Dcaffeine.simulator.policies.1=product.Guava \
 *     --maximumSize=100,500,1_000,1_500,2_000 \
 *     --title=Glimpse
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Command(mixinStandardHelpOptions = true)
public final class Simulate implements Runnable {
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  @Option(names = "--maximumSize", required = true, split = ",",
      description = "The maximum sizes", preprocessor = LongPreprocessor.class)
  private SortedSet<Long> maximumSizes;
  @Option(names = "--metric", required = true, defaultValue = "Hit Rate",
      description = "The metric being compared")
  private String metric;
  @Option(names = "--title", description = "The chart's title", defaultValue = "")
  private String title;
  @Option(names = "--theme", required = true, defaultValue = "light",
      description = "The chart's theme")
  private Theme theme;
  @Option(names = "--outputDir", required = true, description = "The destination directory")
  private Path outputDir;

  @Override
  public void run() {
    var baseName = metric.toLowerCase(US).replace(' ', '_');
    var reports = new TreeMap<Long, Path>();
    for (long maximumSize : maximumSizes) {
      var stopwatch = Stopwatch.createStarted();
      var report = simulate(baseName, maximumSize);
      reports.put(maximumSize, report);

      System.out.printf(US, "%,d: Executed in %s%n", maximumSize, stopwatch);
    }

    if (reports.size() == 1) {
      System.out.printf(US, "Did not generate a chart as only one data point%n");
      System.out.printf(US, "Wrote report to %s%n", reports.values().iterator().next());
      return;
    }

    var combinedReport = combineReports(baseName, reports);
    System.out.printf(US, "Wrote combined report to %s%n", combinedReport);

    var chart = generateChart(baseName, combinedReport);
    System.out.printf(US, "Wrote chart to %s%n", chart);
  }

  /** Runs the simulation for the given maximumSize and returns the csv report */
  private Path simulate(String baseName, long maximumSize) {
    var report = outputDir.resolve(baseName + "_" + maximumSize + ".csv");
    var config = ConfigFactory.parseMap(Map.of(
        "caffeine.simulator.report.format", "csv",
        "caffeine.simulator.maximum-size", maximumSize,
        "caffeine.simulator.report.output", report.toString()))
        .withFallback(ConfigFactory.load());
    var simulator = new Simulator(config);
    simulator.run();
    return report;
  }

  /** Returns a combined report from the individual runs. */
  private Path combineReports(String baseName, SortedMap<Long, Path> inputFiles) {
    var report = outputDir.resolve(baseName + ".csv");
    var combiner = new CombinedCsvReport(inputFiles, metric, report);
    combiner.run();
    return report;
  }

  /** Returns the chart rendered from the combined report. */
  private Path generateChart(String baseName, Path report) {
    var chart = outputDir.resolve(baseName + ".png");
    var plotter = new PlotCsv(report, chart, metric, title, theme.style);
    plotter.run();
    return chart;
  }

  public static void main(String[] args) {
    Logger.getLogger("").setLevel(Level.WARNING);
    new CommandLine(Simulate.class)
        .setColorScheme(Help.defaultColorScheme(Help.Ansi.ON))
        .setCommandName(Simulate.class.getSimpleName())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(args);
  }

  @SuppressWarnings("ImmutableEnumChecker")
  private enum Theme {
    light(ChartStyle.light()),
    dark(ChartStyle.dark());

    final ChartStyle style;

    Theme(ChartStyle style) {
      this.style = style;
    }
  }

  private static final class LongPreprocessor implements IParameterPreprocessor {
    @SuppressWarnings("PMD.ReplaceVectorWithList")
    @Override public boolean preprocess(Stack<String> args,
        CommandSpec commandSpec, ArgSpec argSpec, Map<String, Object> info) {
      args.replaceAll(arg -> arg.replace("_", ""));
      return false;
    }
  }
}
