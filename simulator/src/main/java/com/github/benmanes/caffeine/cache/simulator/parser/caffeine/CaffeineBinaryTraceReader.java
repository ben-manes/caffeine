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
package com.github.benmanes.caffeine.cache.simulator.parser.caffeine;

import static java.util.Objects.requireNonNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader;
import com.github.benmanes.caffeine.cache.tracing.TraceEvent;
import com.github.benmanes.caffeine.cache.tracing.TraceEventFormats;

/**
 * A reader for the Caffeine trace files in the binary format.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineBinaryTraceReader implements TraceReader<TraceEvent> {
  private final Path filePath;

  public CaffeineBinaryTraceReader(Path filePath) {
    this.filePath = requireNonNull(filePath);
  }

  @Override
  public Stream<TraceEvent> events() throws IOException {
    DataInputStream input = new DataInputStream(
        new BufferedInputStream(Files.newInputStream(filePath)));
    Spliterator<TraceEvent> spliterator = Spliterators.spliteratorUnknownSize(
        new BinaryLogIterator(input), Spliterator.NONNULL);
    return StreamSupport.stream(spliterator, false);
  }

  private static final class BinaryLogIterator implements Iterator<TraceEvent> {
    final DataInputStream input;

    BinaryLogIterator(DataInputStream input) {
      this.input = input;
    }

    @Override
    public boolean hasNext() {
      try {
        input.mark(1);
        if (input.readByte() < 0) {
          input.close();
          return false;
        }
        input.reset();
        return true;
      } catch (EOFException e) {
        return false;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public TraceEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      try {
        return TraceEventFormats.readBinaryRecord(input);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
