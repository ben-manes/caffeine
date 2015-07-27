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
 * A skeletal implementation that reads the trace file line by line as textual data.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TextTraceReader<E extends Comparable<E>> implements TraceReader<E> {
  protected final Path filePath;

  protected TextTraceReader(Path filePath) {
    this.filePath = requireNonNull(filePath);
  }

  /** Returns a stream of each line in the trace file. */
  protected Stream<String> lines() throws IOException {
    Path path = resolve(filePath);
    try {
      GZIPInputStream stream = new GZIPInputStream(Files.newInputStream(path));
      Reader reader = new InputStreamReader(stream, Charsets.UTF_8);
      return new BufferedReader(reader, 1 << 16).lines();
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
