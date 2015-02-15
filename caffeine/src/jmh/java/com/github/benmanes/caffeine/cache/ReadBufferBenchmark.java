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
package com.github.benmanes.caffeine.cache;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import com.github.benmanes.caffeine.base.UnsafeAccess;
import com.github.benmanes.caffeine.locks.NonReentrantLock;

/**
 * A concurrent benchmark for read buffer implementation options. A read buffer may be a lossy,
 * bounded, non-blocking, multiple-producer / single-consumer unordered queue. These buffers are
 * used to record a memento of the read operation that occured on the cache, which is later replayed
 * by a single thread on the eviction policy to predict the optimal victim when an entry must be
 * removed.
 * <p>
 * The implementations here are per-segment buffers. In practice the unordered characteristic allows
 * multiple buffers to be used, chosen by a strategy such as hashing on the thread id. A primary
 * goal of the buffer is to maximize read throughput of the cache.
 * <p>
 * The buffer should not create garbage to manage its internal state, such as link nodes. This
 * optimization avoids additional garbage collection pauses that reduces overall throughput.
 * <p>
 * The buffer should try to maintain some bounding constraint to avoid memory pressure under
 * synthetic workloads.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class ReadBufferBenchmark {
  static final int READ_BUFFER_SIZE = 128;
  static final int READ_BUFFER_INDEX_MASK = READ_BUFFER_SIZE - 1;

  @Param BufferType bufferType;
  Buffer buffer;

  @Setup
  public void setup() {
    buffer = bufferType.create();
  }

  @Benchmark @Group @GroupThreads(8)
  public void record() {
    buffer.record();
  }

  @Benchmark @Group @GroupThreads(1)
  public void drain() {
    buffer.drain();
  }

  interface Buffer {
    void record();
    void drain();
  }

  public enum BufferType {
    LOSSY { @Override public Buffer create() { return new LossyBuffer(); } },
    ONE_SHOT { @Override public Buffer create() { return new OneShotBuffer(); } },
    //PTL { @Override public Buffer create() { return new PTLBuffer(); } },
    PER_THREAD { @Override public Buffer create() { return new PerThreadBuffer(); } },
    LOCK { @Override public Buffer create() { return new BoundedQueueBuffer(); } },
    CLQ { @Override public Buffer create() { return new UnboundedQueueBuffer(); } };

    public abstract Buffer create();
  }

  /**
   * A bounded buffer that uses lossy writes and may race with the reader. This design has the
   * benefit of stampeding over other writers to avoid write contention. However, writes may be
   * miss sequenced with the reader resulting in stale entries remaining until the next full pass.
   */
  static final class LossyBuffer implements Buffer {
    final RelaxedAtomic<Boolean>[] buffer;
    final AtomicInteger writeCounter;
    final AtomicInteger readCounter;

    @SuppressWarnings("unchecked")
    LossyBuffer() {
      readCounter = new AtomicInteger();
      writeCounter = new AtomicInteger();
      buffer = new RelaxedAtomic[READ_BUFFER_SIZE];
      for (int i = 0; i < READ_BUFFER_SIZE; i++) {
        buffer[i] = new RelaxedAtomic<Boolean>();
      }
    }

    @Override
    public void record() {
      final int writeCount = writeCounter.get();
      writeCounter.lazySet(writeCount + 1);
      buffer[writeCount & READ_BUFFER_INDEX_MASK].lazySet(Boolean.TRUE);
    }

    @Override
    public void drain() {
      int readCount = readCounter.get();
      for (int i = 0; i < READ_BUFFER_SIZE; i++) {
        int index = readCount & READ_BUFFER_INDEX_MASK;
        Boolean value = buffer[index].getRelaxed();
        if (value == null) {
          break;
        }
        buffer[index].lazySet(null);
        readCount++;
      }
      readCounter.set(readCount);
    }
  }

  /**
   * A bounded buffer that attempts to record once. This design has the benefit of retaining a
   * strict sequence and backing off on contention.
   */
  static final class OneShotBuffer implements Buffer {
    final RelaxedAtomic<Boolean>[] buffer;
    final RelaxedAtomicInt writeCounter;
    final AtomicInteger readCounter;

    @SuppressWarnings("unchecked")
    OneShotBuffer() {
      readCounter = new AtomicInteger();
      writeCounter = new RelaxedAtomicInt();
      buffer = new RelaxedAtomic[READ_BUFFER_SIZE];
      for (int i = 0; i < READ_BUFFER_SIZE; i++) {
        buffer[i] = new RelaxedAtomic<Boolean>();
      }
    }

    @Override
    public void record() {
      final int writeCount = writeCounter.getRelaxed();
      final int index = writeCount & READ_BUFFER_INDEX_MASK;
      if ((buffer[index].getRelaxed() == null) && buffer[index].compareAndSet(null, Boolean.TRUE)) {
        writeCounter.lazySet(writeCount + 1);
      }
    }

    @Override
    public void drain() {
      int readCount = readCounter.get();
      for (int i = 0; i < READ_BUFFER_SIZE; i++) {
        int index = readCount & READ_BUFFER_INDEX_MASK;
        Boolean value = buffer[index].getRelaxed();
        if (value == null) {
          break;
        }
        buffer[index].lazySet(null);
        readCount++;
      }
      readCounter.lazySet(readCount);
    }
  }

  /**
   * A bounded buffer that busy waits until the select slot is free. This design has the benefit of
   * ensuring that the element is recorded while retaining the performance characteristics of a
   * lock-free array.
   *
   * https://blogs.oracle.com/dave/entry/ptlqueue_a_scalable_bounded_capacity
   */
  static final class PTLBuffer implements Buffer {
    final RelaxedAtomic<Boolean>[] buffer;
    final AtomicInteger writeCounter;
    final AtomicInteger readCounter;
    final AtomicIntegerArray turns;

    @SuppressWarnings("unchecked")
    PTLBuffer() {
      turns = new AtomicIntegerArray(READ_BUFFER_SIZE);
      buffer = new RelaxedAtomic[READ_BUFFER_SIZE];
      for (int i = 0; i < READ_BUFFER_SIZE; i++) {
        buffer[i] = new RelaxedAtomic<Boolean>();
        turns.set(i, i);
      }
      writeCounter = new AtomicInteger();
      readCounter = new AtomicInteger();
    }

    @Override
    public void record() {
      final int writeCount = writeCounter.getAndIncrement();
      final int index = writeCount & READ_BUFFER_INDEX_MASK;
      RelaxedAtomic<Boolean> slot = buffer[index];
      while (turns.get(index) != writeCount) {}
      slot.set(Boolean.TRUE);
    }

    @Override
    public void drain() {
      int readCount = readCounter.get();
      for (;;) {
        final int index = readCount & READ_BUFFER_INDEX_MASK;
        RelaxedAtomic<Boolean> slot = buffer[index];
        if (slot.get() == null) {
          break;
        }
        buffer[index].set(null);
        turns.set(index, readCount + READ_BUFFER_SIZE);
        readCount++;
      }
      readCounter.set(readCount);
    }
  }

  /**
   * A per-thread single-producer / single-consumer buffer bounded buffer that add an element only
   * if space is available. This design has the benefit of avoiding contention and has built-in
   * resizing as new threads appear. However, as the thread count increases this strategy uses more
   * memory and increases the drain penalty.
   */
  static final class PerThreadBuffer implements Buffer {
    volatile SpscBuffer[] bufferById;
    volatile int[] idToBuffer;

    PerThreadBuffer() {
      idToBuffer = new int[0];
      bufferById = new SpscBuffer[0];
    }

    @Override
    public void record() {
      getBufferByThreadId().record();
    }

    private SpscBuffer getBufferByThreadId() {
      final int id = (int) Thread.currentThread().getId();
      final int idIndex = id - 1;
      int[] idToBuffer = this.idToBuffer;
      if ((idToBuffer.length >= id) && (idToBuffer[idIndex] != -1)) {
        return bufferById[idToBuffer[idIndex]];
      }
      synchronized (this) {
        idToBuffer = this.idToBuffer;
        int index = idToBuffer.length;
        SpscBuffer[] bufferById = this.bufferById;
        if (idToBuffer.length < id) {
          idToBuffer = Arrays.copyOf(idToBuffer, id);
          bufferById = Arrays.copyOf(bufferById, bufferById.length + 1);
          Arrays.fill(idToBuffer, index, id, -1);
        }
        idToBuffer[idIndex] = index;
        bufferById[index] = new SpscBuffer();
        return bufferById[index];
      }
    }

    @Override
    public void drain() {
      SpscBuffer[] bufferById = this.bufferById;
      for (SpscBuffer buffer : bufferById) {
        buffer.drain();
      }
    }

    /** A single-producer / single-consumer buffer (Oancea et al, 2009). */
    static final class SpscBuffer implements Buffer {
      private static final int PER_THREAD_BUFFER_SIZE = 16;
      private static final int PER_THREAD_BUFFER_INDEX_MASK = PER_THREAD_BUFFER_SIZE - 1;

      private final RelaxedAtomicInt head;
      private final RelaxedAtomicInt tail;
      private final AtomicReferenceArray<Boolean> buffer;

      SpscBuffer() {
        head = new RelaxedAtomicInt();
        tail = new RelaxedAtomicInt();
        buffer = new AtomicReferenceArray<Boolean>(PER_THREAD_BUFFER_SIZE);
      }

      @Override
      public void record() {
        int newTail = (tail.getRelaxed() + 1) & PER_THREAD_BUFFER_INDEX_MASK;
        if (newTail != head.getRelaxed()) {
          buffer.lazySet(newTail, Boolean.TRUE);
          tail.lazySet(newTail);
        }
      }

      @Override
      public void drain() {
        int readCount = head.getRelaxed();
        int writeCount = tail.getRelaxed();
        while (readCount != writeCount) {
          final int index = readCount & PER_THREAD_BUFFER_INDEX_MASK;
          buffer.lazySet(index, null);
          readCount++;
        }
        head.lazySet(readCount);
      }
    }
  }

  /**
   * An unbounded buffer. This provides by baseline by comparing to a simple strategy of delegating
   * to a general purpose {@link ConcurrentLinkedQueue}.
   */
  static final class UnboundedQueueBuffer implements Buffer {
    final Queue<Boolean> buffer = new ConcurrentLinkedQueue<>();

    @Override
    public void record() {
      buffer.add(Boolean.TRUE);
    }

    @Override
    public void drain() {
      while (buffer.poll() != null) {}
    }
  }

  /**
   * An bounded buffer guarded by a try-lock. This design has performs very well when the buffer is
   * full, as an early escape occurs using an uncontended volatile read and draining is fairly slow.
   * This skews the results to this implementation's favor, as the buffer is more often full than
   * not allowing no work to occur. However, ideally there should be low contention in a segmented
   * read buffer resulting in lock performance not being a drawback, so its applicability requires
   * more analysis.
   */
  static final class BoundedQueueBuffer extends NonReentrantLock implements Buffer {
    private static final long serialVersionUID = 1L;

    final Object[] buffer = new Object[READ_BUFFER_SIZE];
    volatile int size;

    @Override
    public void record() {
      if ((size != READ_BUFFER_SIZE) && tryLock()) {
        try {
          if (size == buffer.length) {
            return;
          }
          buffer[size++] = Boolean.TRUE;
        } finally {
          unlock();
        }
      }
    }

    @Override
    public void drain() {
      lock();
      try {
        Arrays.fill(buffer, 0, size, null);
      } finally {
        unlock();
      }
    }
  }

  /** An {@link AtomicReference} like holder that supports relaxed reads. */
  static final class RelaxedAtomic<E> {
    static final long VALUE_OFFSET = UnsafeAccess.objectFieldOffset(RelaxedAtomic.class, "value");

    @SuppressWarnings("unused")
    private volatile E value;

    @SuppressWarnings("unchecked")
    public E get() {
      return (E) UnsafeAccess.UNSAFE.getObjectVolatile(this, VALUE_OFFSET);
    }

    @SuppressWarnings("unchecked")
    public E getRelaxed() {
      return (E) UnsafeAccess.UNSAFE.getObject(this, VALUE_OFFSET);
    }

    public final void set(E newValue) {
      UnsafeAccess.UNSAFE.putObjectVolatile(this, VALUE_OFFSET, newValue);
    }

    public boolean compareAndSet(E expect, E update) {
      return UnsafeAccess.UNSAFE.compareAndSwapObject(this, VALUE_OFFSET, expect, update);
    }

    public final void lazySet(E newValue) {
      UnsafeAccess.UNSAFE.putOrderedObject(this, VALUE_OFFSET, newValue);
    }
  }

  /** An {@link AtomicInteger} like holder that supports relaxed reads. */
  static final class RelaxedAtomicInt {
    static final long VALUE_OFFSET = UnsafeAccess.objectFieldOffset(
        RelaxedAtomicInt.class, "value");

    private volatile int value;

    public int get() {
      return value;
    }

    public int getRelaxed() {
      return UnsafeAccess.UNSAFE.getInt(this, VALUE_OFFSET);
    }

    public final void lazySet(int newValue) {
      UnsafeAccess.UNSAFE.putOrderedInt(this, VALUE_OFFSET, newValue);
    }
  }
}
