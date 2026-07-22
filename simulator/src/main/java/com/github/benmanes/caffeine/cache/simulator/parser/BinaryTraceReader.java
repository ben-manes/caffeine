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
import static java.util.Spliterator.NONNULL;
import static java.util.Spliterator.ORDERED;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.google.common.io.Closeables;
import com.google.common.io.CountingInputStream;

/**
 * A skeletal implementation that reads the trace file as binary data.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class BinaryTraceReader extends AbstractTraceReader {

  protected BinaryTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  @SuppressWarnings("PMD.CloseResource")
  public Stream<AccessEvent> events() {
    var in = readFile();
    var stream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(
        new TraceIterator(in), ORDERED | NONNULL), /* parallel= */ false);
    return stream.onClose(() -> Closeables.closeQuietly(in));
  }

  /** Returns the next event from the input stream. */
  protected abstract AccessEvent readEvent(DataInputStream input) throws IOException;

  private final class TraceIterator implements Iterator<AccessEvent> {
    final CountingInputStream counter;
    final DataInputStream input;
    @Nullable AccessEvent next;
    boolean ready;

    TraceIterator(InputStream in) {
      this.counter = new CountingInputStream(requireNonNull(in));
      this.input = new DataInputStream(counter);
    }

    @Override
    public boolean hasNext() {
      if (ready) {
        return true;
      }
      long start = counter.getCount();
      try {
        next = readEvent(input);
        ready = true;
        return true;
      } catch (EOFException e) {
        if (counter.getCount() != start) {
          throw new UncheckedIOException(
              "Truncated trace: partial record at the end of " + filePath, e);
        }
        return false;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public AccessEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      requireNonNull(next);
      ready = false;
      return next;
    }
  }
}
