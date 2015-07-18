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
package com.github.benmanes.caffeine.cache.simulator.parser;

import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import com.google.common.base.Charsets;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TraceReader<E> {
  protected final Path filePath;

  protected TraceReader(Path filePath) {
    this.filePath = requireNonNull(filePath);
  }

  /**
   * Creates a {@link Stream} that lazily reads the trace file.
   *
   * @param filePath the path to the trace file
   * @return a lazy stream of cache events
   */
  public abstract Stream<E> events() throws IOException;

  /** Returns a stream of each line in the trace file. */
  protected Stream<String> lines() throws IOException {
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
  protected Path resolve(Path filePath) {
    if (filePath.toFile().exists()) {
      return filePath;
    }
    URL url = getClass().getResource(filePath.getFileName().toFile().getName());
    requireNonNull(url, "Could not find file: " + filePath);
    return Paths.get(url.getFile());
  }
}
