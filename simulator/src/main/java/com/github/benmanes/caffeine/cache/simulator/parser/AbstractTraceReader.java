/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.tukaani.xz.XZInputStream;

import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;

/**
 * A skeletal implementation that reads the trace files into a data stream.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class AbstractTraceReader implements TraceReader {
  private static final int BUFFER_SIZE = 1 << 16;

  protected final String filePath;

  protected AbstractTraceReader(String filePath) {
    this.filePath = filePath.trim();
  }

  /** Returns the input stream of the trace data. */
  @SuppressWarnings("PMD.CloseResource")
  protected BufferedInputStream readFile() {
    BufferedInputStream input = null;
    try {
      input = new BufferedInputStream(openFile(), BUFFER_SIZE);
      List<Function<InputStream, InputStream>> extractors = ImmutableList.of(
          this::tryXZ, this::tryCompressed, this::tryArchived);
      for (Function<InputStream, InputStream> extractor : extractors) {
        input.mark(100);
        InputStream next = extractor.apply(input);
        if (next == null) {
          input.reset();
        } else {
          input = new BufferedInputStream(next, BUFFER_SIZE);
        }
      }
      return input;
    } catch (Throwable t) {
      try {
        if (input != null) {
          input.close();
        }
      } catch (IOException e) {
        t.addSuppressed(e);
      }
      Throwables.throwIfUnchecked(t);
      throw new RuntimeException(t);
    }
  }

  /** Returns a uncompressed stream if XZ encoded, else {@code null}. */
  private @Nullable InputStream tryXZ(InputStream input) {
    try {
      return new XZInputStream(input);
    } catch (IOException e) {
      return null;
    }
  }

  /** Returns a uncompressed stream, else {@code null}. */
  private @Nullable InputStream tryCompressed(InputStream input) {
    try {
      return new CompressorStreamFactory().createCompressorInputStream(input);
    } catch (CompressorException e) {
      return null;
    }
  }

  /** Returns a unarchived stream, else {@code null}. */
  private @Nullable InputStream tryArchived(InputStream input) {
    try {
      ArchiveInputStream archive = new ArchiveStreamFactory().createArchiveInputStream(input);
      Iterator<InputStream> entries = new AbstractIterator<InputStream>() {
        @Override protected InputStream computeNext() {
          try {
            return (archive.getNextEntry() == null) ? endOfData() : archive;
          } catch (IOException e) {
            return endOfData();
          }
        }
      };
      return new SequenceInputStream(Iterators.asEnumeration(entries));
    } catch (ArchiveException e) {
      return null;
    }
  }

  /** Returns the input stream for the raw file. */
  private InputStream openFile() throws IOException {
    Path file = Paths.get(filePath);
    if (Files.exists(file)) {
      return Files.newInputStream(file);
    }
    InputStream input = getClass().getResourceAsStream(filePath);
    checkArgument(input != null, "Could not find file: %s", filePath);
    return input;
  }
}
