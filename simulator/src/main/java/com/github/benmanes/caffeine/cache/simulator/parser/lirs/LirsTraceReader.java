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
package com.github.benmanes.caffeine.cache.simulator.parser.lirs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import com.google.common.base.Charsets;

/**
 * A reader for the trace files provided by the authors of the LIRS algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LirsTraceReader {

  private LirsTraceReader() {}

  /**
   * Creates a {@link Stream} that lazily reads the trace file.
   *
   * @param filePath the path to the trace file
   * @return a lazy stream of cache events
   */
  public static Stream<String> traceStream(Path filePath) throws IOException {
    return lines(filePath)
        .map(line -> line.trim())
        .filter(line -> !line.equals("*"));
  }

  private static Stream<String> lines(Path filePath) throws IOException {
    Path path = resolve(filePath);
    try {
      GZIPInputStream stream = new GZIPInputStream(Files.newInputStream(path));
      Reader reader = new InputStreamReader(stream, Charsets.UTF_8);
      return new BufferedReader(reader).lines();
    } catch (ZipException e) {
      return Files.lines(path);
    }
  }

  /** Returns the relative file if the full path is not provided. */
  private static Path resolve(Path filePath) {
    if (filePath.toFile().exists()) {
      return filePath;
    }
    return Paths.get(LirsTraceReader.class.getResource(
        filePath.getFileName().toFile().getName()).getFile());
  }
}
