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
package com.github.benmanes.caffeine.cache.simulator.parser.cache2k;

import static java.util.Objects.requireNonNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

import com.github.benmanes.caffeine.cache.simulator.parser.AbstractTraceReader;
import com.google.common.io.Closeables;

/**
 * A reader for the trace files provided by the author of cache2k. See
 * <a href="https://github.com/cache2k/cache2k-benchmark">traces</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Cache2kTraceReader extends AbstractTraceReader {

  public Cache2kTraceReader(List<String> filePaths) {
    super(filePaths);
  }

  @Override
  public LongStream events() throws IOException {
    DataInputStream input = new DataInputStream(new BufferedInputStream(readFiles()));
    LongStream stream = StreamSupport.longStream(Spliterators.spliteratorUnknownSize(
        new TraceIterator(input), Spliterator.ORDERED), /* parallel */ false);
    return stream.onClose(() -> Closeables.closeQuietly(input));
  }

  private static final class TraceIterator implements PrimitiveIterator.OfLong {
    final DataInputStream input;

    TraceIterator(DataInputStream input) {
      this.input = requireNonNull(input);
    }

    @Override
    public boolean hasNext() {
      try {
        input.mark(100);
        input.readInt();
        input.reset();
        return true;
      } catch (EOFException e) {
        return false;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public long nextLong() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      try {
        return input.readInt();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }
}
