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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.common.base.Charsets;
import com.google.common.io.Closeables;

/**
 * A skeletal implementation that reads the trace file line by line as textual data.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TextTraceReader<E extends Comparable<E>> implements TraceReader<E> {
  private final String filePath;

  public TextTraceReader(String filePath) {
    this.filePath = requireNonNull(filePath);
  }

  /** Returns a stream of each line in the trace file. */
  protected Stream<String> lines() throws IOException {
    IOException e = null;
    for (boolean compressed : new boolean[] { true, false }) {
      InputStream input = openFile();
      try {
        InputStream stream = compressed ? new GZIPInputStream(input) : input;
        Reader reader = new InputStreamReader(stream, Charsets.UTF_8);
        return new BufferedReader(reader, 1 << 16).lines().map(String::trim)
            .onClose(() -> Closeables.closeQuietly(input));
      } catch (IOException ex) {
        input.close();
        e = ex;
      }
    }
    throw e;
  }

  /** Returns the input stream for the raw file. */
  private InputStream openFile() throws IOException {
    File file = new File(filePath);
    if (file.exists()) {
      return new FileInputStream(file);
    }
    InputStream input = getClass().getResourceAsStream(filePath);
    checkArgument(input != null, "Could not find file: " + filePath);
    return input;
  }
}
