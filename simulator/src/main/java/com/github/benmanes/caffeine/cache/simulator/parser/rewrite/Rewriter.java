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
package com.github.benmanes.caffeine.cache.simulator.parser.rewrite;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceFormat;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.google.common.base.Stopwatch;

/**
 * A simple utility to rewrite traces for into the format used by other simulators. This lets us
 * run multiple simulators in parallel for a quick-and-dirty analysis, rather than port their code
 * into Java.
 * <p>
 * <pre>{@code
 *   ./gradlew :simulator:rewrite -PinputFiles=? -PoutputFile=? -PoutputFormat=?
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.ImmutableField")
public final class Rewriter {
  @Parameter(names = "--inputFiles", required = true, description = "The trace input files. To use "
      + "a mix of formats, specify the entry as format:path, e.g. lirs:loop.trace.gz")
  private List<String> inputFiles = new ArrayList<>();
  @Parameter(names = "--inputFormat", description = "The default trace input format")
  private TraceFormat inputFormat = TraceFormat.LIRS;

  @Parameter(names = "--outputFile", description = "The trace output file", required = true)
  private Path outputFile;
  @Parameter(names = "--outputFormat", description = "The trace output format", required = true)
  private OutputFormat outputFormat;

  @Parameter(names = "--help", help = true, hidden = true)
  private boolean help;

  @SuppressWarnings("PMD.ForLoopCanBeForeach")
  public void run() throws IOException {
    int count = 0;
    Stopwatch stopwatch = Stopwatch.createStarted();
    try (Stream<AccessEvent> events = inputFormat.readFiles(inputFiles).events();
         BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
      for (Iterator<AccessEvent> i = events.iterator(); i.hasNext();) {
        outputFormat.write(writer, i.next().key());
        count++;
      }
    }
    System.out.printf("Rewrote %,d events in %s%n", count, stopwatch);
  }

  public static void main(String[] args) throws IOException {
    Rewriter rewriter = new Rewriter();
    JCommander commander =  JCommander.newBuilder()
        .programName(Rewriter.class.getSimpleName())
        .addObject(rewriter)
        .build();
    commander.parse(args);
    if (rewriter.help) {
      commander.usage();
    } else {
      rewriter.run();
    }
  }
}
