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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.function.UnaryOperator;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.jspecify.annotations.Nullable;
import org.tukaani.xz.XZInputStream;

import com.google.common.base.Throwables;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import com.google.errorprone.annotations.Var;

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
  protected BufferedInputStream readFile() {
    try {
      return readInput(openFile());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @SuppressWarnings("PMD.CloseResource")
  protected BufferedInputStream readInput(InputStream input) {
    @Var BufferedInputStream bufferedStream = null;
    try {
      bufferedStream = new BufferedInputStream(input, BUFFER_SIZE);
      var extractors = List.<UnaryOperator<InputStream>>of(
          AbstractTraceReader::tryXz, AbstractTraceReader::tryCompressed, this::tryArchived);
      for (var extractor : extractors) {
        bufferedStream.mark(100);
        InputStream next = extractor.apply(bufferedStream);
        if (next == null) {
          bufferedStream.reset();
        } else if (next instanceof BufferedInputStream buffered) {
          bufferedStream = buffered;
        } else {
          bufferedStream = new BufferedInputStream(next, BUFFER_SIZE);
        }
      }
      return bufferedStream;
    } catch (Throwable t) {
      try {
        if (bufferedStream != null) {
          bufferedStream.close();
        }
      } catch (IOException e) {
        t.addSuppressed(e);
      }
      Throwables.throwIfUnchecked(t);
      throw new IllegalStateException(t);
    }
  }

  /** Returns an uncompressed stream if XZ encoded, else {@code null}. */
  private static @Nullable InputStream tryXz(InputStream input) {
    try {
      return new XZInputStream(input);
    } catch (IOException e) {
      return null;
    }
  }

  /** Returns an uncompressed stream, else {@code null}. */
  private static @Nullable InputStream tryCompressed(InputStream input) {
    try {
      return new CompressorStreamFactory().createCompressorInputStream(input);
    } catch (CompressorException e) {
      return null;
    }
  }

  /** Returns an unarchived stream, else {@code null}. */
  private @Nullable InputStream tryArchived(InputStream input) {
    try {
      var archive = new ArchiveStreamFactory().createArchiveInputStream(input);
      var entries = new AbstractIterator<InputStream>() {
        @Override protected @Nullable InputStream computeNext() {
          try {
            return (archive.getNextEntry() == null)
                ? endOfData()
                : readInput(CloseShieldInputStream.wrap(archive));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      };
      return new MultiInputStream(archive, entries);
    } catch (ArchiveException e) {
      return null;
    }
  }

  /** Returns the input stream for the raw file. */
  private InputStream openFile() throws IOException {
    var file = Path.of(filePath);
    if (Files.exists(file)) {
      return Files.newInputStream(file);
    }
    InputStream input = getClass().getResourceAsStream(filePath);
    checkArgument(input != null, "Could not find file: %s", filePath);
    return input;
  }

  private static final class MultiInputStream extends FilterInputStream {
    private final InputStream parent;

    public MultiInputStream(InputStream parent, Iterator<InputStream> children) {
      super(new SequenceInputStream(Iterators.asEnumeration(children)));
      this.parent = parent;
    }
    @Override public void close() throws IOException {
      parent.close();
    }
  }
}
