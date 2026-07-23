/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Objects.requireNonNull;
import static java.util.Spliterator.NONNULL;
import static java.util.Spliterator.ORDERED;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.mutable.MutableObject;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import com.google.errorprone.annotations.MustBeClosed;
import com.google.errorprone.annotations.Var;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * A trace reader that materializes the trace once, precomputing each request's next-access time, so
 * that the clairvoyant policies ({@code opt.Clairvoyant}, {@code admission.Clairvoyant}) can share
 * a single {@literal Bélády} look-ahead.
 * <p>
 * When any clairvoyant usage is enabled, the simulator wraps the underlying reader with this one.
 * On construction it materializes the trace to a fixed-width temporary file: a forward pass appends
 * each request, then a backward pass fills each request's next-access pointer from a
 * {@code nextSeen} map (one entry per distinct key). Both passes are sequential and the mapping is
 * released before the policies are built, so the look-ahead memory is isolated to construction;
 * the materialization also freezes an otherwise non-repeatable synthetic sequence so every consumer
 * walks the identical trace.
 * <p>
 * The materialized events are replayed to all policies through {@link #events()} and the
 * clairvoyant consumers additionally walk the next-access times sequentially through a
 * {@link Cursor} in lockstep with the replay. Bélády only ever needs a key's <em>immediate</em>
 * next use, so a single pointer per request suffices.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ClairvoyantTraceReader implements TraceReader {
  private static final ScopedValue<ClairvoyantTraceReader> READER = ScopedValue.newInstance();
  private static final int BUFFER_SIZE = 1 << 20;
  /** The sentinel for a request whose key is never accessed again. */
  public static final long NONE = -1;

  private final Set<Characteristic> characteristics;
  private final Collection<Cursor> cursors;
  private final boolean penaltyAware;
  private final boolean weighted;
  private final int recordWidth;
  private final long count;
  private final Path path;

  public ClairvoyantTraceReader(TraceReader delegate, long skip, long limit) {
    var materialized = materialize(delegate, skip, limit);
    characteristics = delegate.characteristics();
    weighted = characteristics.contains(WEIGHTED);
    penaltyAware = materialized.penaltyAware;
    cursors = new ConcurrentLinkedQueue<>();
    recordWidth = materialized.recordWidth;
    count = materialized.count;
    path = materialized.path;
  }

  @Override
  public Set<Characteristic> characteristics() {
    return characteristics;
  }

  @Override
  @MustBeClosed
  @SuppressWarnings("PMD.CloseResource")
  public Stream<AccessEvent> events() {
    var input = openInput();
    var stream = StreamSupport.stream(Spliterators.spliterator(
        new EventIterator(input), count, ORDERED | NONNULL), /* parallel= */ false);
    return stream.onClose(() -> closeQuietly(input));
  }

  /**
   * Runs the action with this reader installed as the active clairvoyant source, so a usage
   * can obtain a {@link Cursor} from {@link #currentCursor()}.
   */
  public <R> R scoped(Supplier<R> action) {
    return ScopedValue.where(READER, this).call(action::get);
  }

  /** Returns a cursor over the active reader's next-access times, if any is installed. */
  public static Optional<Cursor> currentCursor() {
    return READER.isBound() ? Optional.of(READER.get().newCursor()) : Optional.empty();
  }

  /** Returns a fresh sequential cursor over the next-access times. */
  public Cursor newCursor() {
    var cursor = new Cursor(openInput(), recordWidth - Long.BYTES, count);
    cursors.add(cursor);
    return cursor;
  }

  @Override
  public void close() {
    cursors.forEach(Cursor::close);
    cursors.clear();
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private DataInputStream openInput() {
    try {
      return new DataInputStream(new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Materializes the trace to the next-access file. A forward pass appends each request; a backward
   * pass then fills each request's next-access pointer from a {@code nextSeen} map. Both passes are
   * fully sequential where the backward pass avoids the random writes that a forward back-fill
   * would incur for long reuse distances (its window can't span the whole trace) and hold only one
   * entry per distinct key on the heap.
   */
  @SuppressWarnings("MustBeClosedChecker")
  private Materialized materialize(TraceReader delegate, long skip, long limit) {
    try {
      var file = Files.createTempFile("clairvoyant", ".oracle");
      var state = new Materialized(file, /* penaltyAware= */ false, /* recordWidth= */ 0);
      try (var channel = FileChannel.open(file, READ, WRITE)) {
        if (delegate instanceof TraceReader.KeyOnlyTraceReader keyReader) {
          materializeKeys(keyReader, channel, state, skip, limit);
        } else {
          materializeEvents(delegate, channel, state, skip, limit);
        }
        fillNextAccess(channel, state.recordWidth, state.count);
      }
      return state;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The backward pass: fills each request's next-access pointer, one block at a time. */
  private static void fillNextAccess(FileChannel channel, int recordWidth, long count) {
    if (count == 0) {
      return;
    }
    var nextSeen = new Long2LongOpenHashMap();
    int blockRecords = Math.max(1, BUFFER_SIZE / recordWidth);
    var block = ByteBuffer.allocate(blockRecords * recordWidth);
    nextSeen.defaultReturnValue(NONE);
    @Var long blockEnd = count;
    while (blockEnd > 0) {
      long blockStart = Math.max(0, blockEnd - blockRecords);
      block.clear().limit(recordWidth * (int) (blockEnd - blockStart));
      readFully(channel, block, blockStart * recordWidth);
      for (@Var long position = blockEnd - 1; position >= blockStart; position--) {
        int offset = recordWidth * (int) (position - blockStart);
        long nextAccess = nextSeen.put(block.getLong(offset), position);
        block.putLong(offset + (recordWidth - Long.BYTES), nextAccess);
      }
      writeFully(channel, block.flip(), blockStart * recordWidth);
      blockEnd = blockStart;
    }
  }

  /** The fast path for a key-only trace: reads the primitive keys with no per-event boxing. */
  private static void materializeKeys(TraceReader.KeyOnlyTraceReader delegate,
      FileChannel channel, Materialized state, long skip, long limit) {
    state.recordWidth = recordWidth(/* weighted= */ false, /* penaltyAware= */ false);
    var writer = new Writer(channel, state.recordWidth,
        /* weighted= */ false, /* penaltyAware= */ false);
    try (var keys = delegate.keys().skip(skip).limit(limit)) {
      keys.forEachOrdered(key -> {
        writer.append(key);
        state.count++;
      });
    }
    writer.flush();
  }

  /** The general path for a trace carrying weight or penalties. */
  @SuppressWarnings("MustBeClosedChecker")
  private void materializeEvents(TraceReader delegate, FileChannel channel,
      Materialized state, long skip, long limit) {
    var writer = new MutableObject<Writer>();
    try (Stream<AccessEvent> events = delegate.events().skip(skip).limit(limit)) {
      events.forEachOrdered(event -> {
        if (writer.get() == null) {
          state.penaltyAware = event.isPenaltyAware();
          state.recordWidth = recordWidth(weighted, state.penaltyAware);
          writer.setValue(new Writer(channel, state.recordWidth, weighted, state.penaltyAware));
        } else {
          checkState(state.penaltyAware == event.isPenaltyAware(),
              "Cannot mix penalty-aware and non-penalty-aware events");
        }
        writer.get().append(event);
        state.count++;
      });
    }
    if (writer.get() != null) {
      writer.get().flush();
    }
  }

  private static int recordWidth(boolean weighted, boolean penaltyAware) {
    int shape = weighted ? Integer.BYTES : (penaltyAware ? (2 * Double.BYTES) : 0);
    return Long.BYTES + shape + Long.BYTES;
  }

  private static void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) {
    @Var long at = position;
    try {
      while (buffer.hasRemaining()) {
        at += channel.write(buffer, at);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void readFully(FileChannel channel, ByteBuffer buffer, long position) {
    @Var long at = position;
    try {
      while (buffer.hasRemaining()) {
        int n = channel.read(buffer, at);
        if (n < 0) {
          throw new UncheckedIOException(new EOFException("Truncated oracle file"));
        }
        at += n;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Appends into fixed-width records where the next-access pointer is filled by a later pass. */
  private static final class Writer {
    private final boolean penaltyAware;
    private final boolean weighted;
    private final int recordWidth;

    private final FileChannel channel;
    private final ByteBuffer buffer;

    private long base;

    Writer(FileChannel channel, int recordWidth, boolean weighted, boolean penaltyAware) {
      this.buffer = ByteBuffer.allocate((BUFFER_SIZE / recordWidth) * recordWidth);
      this.penaltyAware = penaltyAware;
      this.recordWidth = recordWidth;
      this.weighted = weighted;
      this.channel = channel;
    }

    void append(AccessEvent event) {
      if (!buffer.hasRemaining()) {
        flush();
      }
      buffer.putLong(event.key());
      if (weighted) {
        buffer.putInt(event.weight());
      } else if (penaltyAware) {
        buffer.putDouble(event.hitPenalty());
        buffer.putDouble(event.missPenalty());
      }
      buffer.putLong(NONE);
    }

    /** Appends a key-only (plain) record without boxing the key into an event. */
    void append(long key) {
      if (!buffer.hasRemaining()) {
        flush();
      }
      buffer.putLong(key);
      buffer.putLong(NONE);
    }

    void flush() {
      int filled = buffer.position();
      writeFully(channel, buffer.flip(), base * recordWidth);
      base += (filled / recordWidth);
      buffer.clear();
    }
  }

  /** Streams the reconstructed events from the materialized file. */
  private final class EventIterator implements Iterator<AccessEvent> {
    final DataInputStream input;

    long position;

    EventIterator(DataInputStream input) {
      this.input = input;
    }

    @Override
    public boolean hasNext() {
      return position < count;
    }

    @Override
    public AccessEvent next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      try {
        long key = input.readLong();
        AccessEvent event;
        if (weighted) {
          event = AccessEvent.forKeyAndWeight(key, input.readInt());
        } else if (penaltyAware) {
          event = AccessEvent.forKeyAndPenalties(key, input.readDouble(), input.readDouble());
        } else {
          event = AccessEvent.forKey(key);
        }
        input.skipNBytes(Long.BYTES);
        position++;
        return event;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /** A sequential walk over the next-access times, one per request in trace order. */
  public static final class Cursor implements AutoCloseable {
    private final DataInputStream input;
    private final long count;
    private final int skip;

    private long position;

    Cursor(DataInputStream input, int skip, long count) {
      this.input = input;
      this.count = count;
      this.skip = skip;
    }

    /** Returns the next-access time of the next request, or {@link #NONE} if never again. */
    public long next() {
      if (position >= count) {
        throw new NoSuchElementException();
      }
      try {
        input.skipNBytes(skip);
        long nextAccess = input.readLong();
        position++;
        return nextAccess;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void close() {
      closeQuietly(requireNonNull(input));
    }
  }

  /** The mutable result of the materialization pass. */
  private static final class Materialized {
    final Path path;

    boolean penaltyAware;
    int recordWidth;
    long count;

    Materialized(Path path, boolean penaltyAware, int recordWidth) {
      this.penaltyAware = penaltyAware;
      this.recordWidth = recordWidth;
      this.path = path;
    }
  }
}
