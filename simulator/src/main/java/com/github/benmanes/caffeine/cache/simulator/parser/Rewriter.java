/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.google.common.base.Stopwatch;

import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Option;

/**
 * A simple utility to rewrite traces for into the format used by other simulators. This lets us
 * run multiple simulators in parallel for a quick-and-dirty analysis, rather than port their code
 * into Java.
 * <p>
 * <pre>{@code
 *   ./gradlew :simulator:rewrite \
 *      -PinputFormat=? \
 *      -PinputFiles=? \
 *      -PoutputFile=? \
 *      -PoutputFormat=?
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.ImmutableField")
public final class Rewriter implements Runnable {
  @Option(names = "--inputFiles", required = true, description = "The trace input files. To use "
      + "a mix of formats, specify the entry as format:path, e.g. lirs:loop.trace.gz")
  private List<String> inputFiles = new ArrayList<>();
  @Option(names = "--inputFormat", required = true, description = "The default trace input format")
  private TraceFormat inputFormat;

  @Option(names = "--outputFile", required = true, description = "The trace output file")
  private Path outputFile;
  @Option(names = "--outputFormat", required = true, description = "The trace output format")
  private OutputFormat outputFormat;

  @Override
  public void run() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(outputFile));
         Stream<AccessEvent> events = inputFormat.readFiles(inputFiles).events();
         TraceWriter writer = outputFormat.writer(output)) {
      int[] tick = { 0 };
      writer.writeHeader();
      events.forEach(event -> {
        try {
          writer.writeEvent(tick[0], event);
          tick[0]++;
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      writer.writeFooter();
      System.out.printf("Rewrote %,d events in %s%n", tick[0], stopwatch);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void main(String[] args) throws IOException {
    new CommandLine(Rewriter.class)
        .setColorScheme(Help.defaultColorScheme(Help.Ansi.ON))
        .setCommandName(Rewriter.class.getSimpleName())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(args);
  }
}
