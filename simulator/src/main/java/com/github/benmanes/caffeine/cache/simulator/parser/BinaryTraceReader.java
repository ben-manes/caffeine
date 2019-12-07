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

import static java.util.Objects.requireNonNull;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.common.io.Closeables;

/**
 * A skeletal implementation that reads the trace file as binary data.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class BinaryTraceReader extends AbstractTraceReader {

  public BinaryTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  @SuppressWarnings("PMD.CloseResource")
  public Stream<AccessEvent> events() throws IOException {
    DataInputStream input = new DataInputStream(new BufferedInputStream(readFile()));
    Stream<AccessEvent> stream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(
        new TraceIterator(input), Spliterator.ORDERED), /* parallel */ false);
    return stream.onClose(() -> Closeables.closeQuietly(input));
  }

  /** Returns the next event from the input stream. */
  protected abstract long readLong(DataInputStream input) throws IOException;

  public final class TraceIterator implements PrimitiveIterator<AccessEvent, LongConsumer> {
    final DataInputStream input;
    boolean ready;
    long nextKey;

    TraceIterator(DataInputStream input) {
      this.input = requireNonNull(input);
    }

    @Override
    public boolean hasNext() {
      if (ready) {
        return true;
      }
      try {
        nextKey = readLong(input);
        ready = true;
        return true;
      } catch (EOFException e) {
        return false;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public AccessEvent next() {
      return new AccessEvent.AccessEventBuilder(nextKey).build();
    }

    public long nextLong() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      ready = false;
      return nextKey;
    }

    @Override
    public void forEachRemaining(LongConsumer longConsumer) {
      Objects.requireNonNull(longConsumer);

      while(this.hasNext()) {
        longConsumer.accept(this.nextLong());
      }
    }
  }
}
