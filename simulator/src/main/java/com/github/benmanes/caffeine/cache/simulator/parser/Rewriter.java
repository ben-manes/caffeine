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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
 *   ./gradlew :simulator:rewrite -q \
 *      -PinputFormat=? \
 *      -PinputFiles=? \
 *      -PoutputFile=? \
 *      -PoutputFormat=?
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Rewriter implements Runnable {
  @Option(names = "--inputFiles", required = true, split = ",", description = "The trace input "
      + "files. To use a mix of formats, specify the entry as format:path, e.g. lirs:loop.trace.gz")
  private List<String> inputFiles;
  @Option(names = "--inputFormat", required = true, description = "The default trace input format")
  private TraceFormat inputFormat;

  @Option(names = "--outputFile", required = true, description = "The trace output file")
  private Path outputFile;
  @Option(names = "--outputFormat", required = true, description = "The trace output format")
  private OutputFormat outputFormat;

  @Override
  public void run() {
    var stopwatch = Stopwatch.createStarted();
    try (var output = new BufferedOutputStream(Files.newOutputStream(outputFile));
         var events = inputFormat.readFiles(inputFiles).events();
         var writer = outputFormat.writer(output)) {
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
      System.out.printf("Rewrote %,d events from %,d inputs in %s%n",
          tick[0], inputFiles.size(), stopwatch);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void main(String[] args) {
    new CommandLine(Rewriter.class)
        .setColorScheme(Help.defaultColorScheme(Help.Ansi.ON))
        .setCommandName(Rewriter.class.getSimpleName())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(args);
  }
}
