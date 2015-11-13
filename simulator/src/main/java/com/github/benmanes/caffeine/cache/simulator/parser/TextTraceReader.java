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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import com.google.common.base.Charsets;
import com.google.common.io.Closeables;

/**
 * A skeletal implementation that reads the trace file line by line as textual data.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TextTraceReader implements TraceReader {
  private final String filePath;

  public TextTraceReader(String filePath) {
    this.filePath = requireNonNull(filePath);
  }

  /** Returns a stream of each line in the trace file. */
  protected Stream<String> lines() throws IOException {
    InputStream input = readFile();
    Reader reader = new InputStreamReader(input, Charsets.UTF_8);
    return new BufferedReader(reader, 1 << 16).lines().map(String::trim)
        .onClose(() -> Closeables.closeQuietly(input));
  }

  /** Returns the input stream, decompressing if required. */
  private InputStream readFile() throws IOException {
    BufferedInputStream input = new BufferedInputStream(openFile());
    input.mark(100);
    try {
      return new CompressorStreamFactory().createCompressorInputStream(input);
    } catch (CompressorException e) {
      input.reset();
    }
    try {
      return new ArchiveStreamFactory().createArchiveInputStream(input);
    } catch (ArchiveException e) {
      input.reset();
    }
    return input;
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
