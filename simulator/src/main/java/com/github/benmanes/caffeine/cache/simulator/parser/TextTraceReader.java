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
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import javax.annotation.Nullable;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;

import com.google.common.base.Charsets;

/**
 * A skeletal implementation that reads the trace file line by line as textual data.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class TextTraceReader<E extends Comparable<E>> implements TraceReader<E> {
  protected final String filePath;

  protected TextTraceReader(String filePath) {
    this.filePath = requireNonNull(FilenameUtils.normalize(filePath));
  }

  /** Returns a stream of each line in the trace file. */
  protected Stream<String> lines() throws IOException {
    Stream<String> gzipStream = gzipStream();
    return (gzipStream == null) ? fileStream() : gzipStream;
  }

  /** Returns the trace file stream if gzip'ed, otherwise null. */
  private @Nullable Stream<String> gzipStream() throws IOException {
    InputStream input = openFile();
    try {
      GZIPInputStream stream = new GZIPInputStream(input);
      Reader reader = new InputStreamReader(stream, Charsets.UTF_8);
      return new BufferedReader(reader, 1 << 16).lines();
    } catch (ZipException e) {
      input.close();
      return null;
    }
  }

  /** Returns the trace file stream. */
  private Stream<String> fileStream() throws IOException {
    LineIterator lines = IOUtils.lineIterator(new InputStreamReader(openFile(), Charsets.UTF_8));
    Spliterator<String> spliterator =
        Spliterators.spliteratorUnknownSize(lines, Spliterator.NONNULL);
    return StreamSupport.stream(spliterator, false).onClose(lines::close);
  }

  /** Returns an open input stream for the trace file. */
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
