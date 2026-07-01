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
package com.github.benmanes.caffeine.cache;

import static java.lang.invoke.ConstantBootstraps.fieldVarHandle;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import org.jspecify.annotations.Nullable;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;

import com.google.errorprone.annotations.Var;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A stress test for {@link BoundedBuffer.RingBuffer}'s read-counter publication. The consumer
 * (drain) clears each slot with a release store and then advances the read count; a lock-free
 * producer (offer) volatile-reads the read count and, seeing space, republishes a slot. If the read
 * count is published with a weaker-than-release store then a producer that observes the advance has
 * no happens-before edge to the preceding slot clears: a clear can land after the producer's write
 * and strand the slot, since the drain treats the resulting null as an unpublished entry and never
 * advances past it (a permanently full stripe).
 * <p>
 * This is a formal hole in the Java and aarch64 memory models: {@code setOpaque} is not a release,
 * so the producer's volatile read does not synchronize-with it, and a store-release orders only
 * prior accesses, not a subsequent relaxed store. It is not reachable in {@code BoundedBuffer} on
 * current hardware: the ring buffer places a full wrap (up to {@code BUFFER_SIZE} offers) between a
 * slot's clear and its reuse, so the relaxed count store would have to be observed after passing
 * that many release stores. The isolated {@code Opaque} case removes the wrap (a single slot reused
 * immediately) and does reproduce the stranded slot on an M3 Max; {@code Simple} and {@code Actual}
 * exercise the real 16-slot structure with the shipped {@code setRelease} and forbid it.
 * <p>
 * {@snippet lang="shell" :
 * ./gradlew caffeine:jcstress -PjavaVersion=21 --tests RingBufferPublication --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"JavadocDeclaration", "Varifier"})
@SuppressFBWarnings({"URF_UNREAD_FIELD", "UUF_UNUSED_FIELD"})
public final class RingBufferPublication {
  static final int SIZE = BoundedBuffer.BUFFER_SIZE;
  static final int MASK = SIZE - 1;
  static final Integer ELEMENT = SIZE;

  private RingBufferPublication() {}

  /** A standalone model of the ring buffer, mirroring the real structure without its plumbing. */
  @State
  @JCStressTest
  @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "the offered element was drained")
  @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "buffer full, the element was not offered")
  @Outcome(id = "-1, 0", expect = ACCEPTABLE, desc = "CAS failed, the element was not offered")
  @Outcome(id = "0, 0", expect = FORBIDDEN, desc = "offered but stranded: the element vanished")
  public static class Simple {
    static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);
    static final VarHandle READ = fieldVarHandle(MethodHandles.lookup(),
        "readCounter", VarHandle.class, Simple.class, long.class);
    static final VarHandle WRITE = fieldVarHandle(MethodHandles.lookup(),
        "writeCounter", VarHandle.class, Simple.class, long.class);

    final @Nullable Object[] buffer = new Object[SIZE];

    volatile long writeCounter;
    volatile long readCounter;
    volatile boolean drained;

    @SuppressWarnings("this-escape")
    public Simple() {
      for (int i = 0; i < SIZE; i++) {
        buffer[i] = i;
      }
      WRITE.set(this, SIZE);
    }

    int offer(Object e) {
      long head = readCounter;
      long tail = (long) WRITE.getOpaque(this);
      if ((tail - head) >= SIZE) {
        return 1; // full
      }
      if (WRITE.weakCompareAndSet(this, tail, tail + 1)) {
        SLOT.setRelease(buffer, (int) (tail & MASK), e);
        return 0; // success
      }
      return -1; // failed
    }

    void drainTo() {
      @Var long head = readCounter;
      long tail = (long) WRITE.getOpaque(this);
      while (head != tail) {
        int index = (int) (head & MASK);
        var e = SLOT.getAcquire(buffer, index);
        if (e == null) {
          break;
        }
        SLOT.setRelease(buffer, index, null);
        if (e.equals(ELEMENT)) {
          drained = true;
        }
        head++;
      }
      READ.setRelease(this, head);
    }

    @Actor
    public void consumer() {
      drainTo();
    }

    @Actor
    public void producer(II_Result r) {
      r.r1 = offer(ELEMENT);
    }

    @Arbiter
    public void arbiter(II_Result r) {
      drainTo();
      r.r2 = drained ? 1 : 0;
    }
  }

  /** Exercises the real ring buffer. */
  @State
  @JCStressTest
  @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "the offered element was drained")
  @Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "buffer full, the element was not offered")
  @Outcome(id = "-1, 0", expect = ACCEPTABLE, desc = "CAS failed, the element was not offered")
  @Outcome(id = "0, 0", expect = FORBIDDEN, desc = "offered but stranded: the element vanished")
  public static class Actual {
    final BoundedBuffer.RingBuffer<Integer> buffer;
    volatile boolean drained;

    @SuppressWarnings("CheckReturnValue")
    public Actual() {
      buffer = new BoundedBuffer.RingBuffer<>(0);
      for (int i = 1; i < SIZE; i++) {
        buffer.offer(i);
      }
    }

    @Actor
    public void consumer() {
      buffer.drainTo(e -> {
        if (e.equals(ELEMENT)) {
          drained = true;
        }
      });
    }

    @Actor
    public void producer(II_Result r) {
      r.r1 = buffer.offer(ELEMENT);
    }

    @Arbiter
    public void arbiter(II_Result r) {
      buffer.drainTo(e -> {
        if (e.equals(ELEMENT)) {
          drained = true;
        }
      });
      r.r2 = drained ? 1 : 0;
    }
  }

  /**
   * The degenerate single-slot case: with no wrap, a cleared slot is reused immediately, so the
   * relaxed {@code setOpaque} count store only has to pass a single release. This reproduces the
   * stranded slot on aarch64 and demonstrates the publication race in isolation; it is not a model
   * of {@code BoundedBuffer}, whose wrap keeps it from being reached on current hardware.
   */
  @State
  @JCStressTest
  @Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "producer did not observe the advanced counter")
  @Outcome(id = "1, 1", expect = ACCEPTABLE, desc = "producer's element survived")
  @Outcome(id = "1, 0", expect = ACCEPTABLE_INTERESTING, desc = "reused slot stranded (aarch64)")
  public static class Opaque {
    static final VarHandle SLOT = fieldVarHandle(MethodHandles.lookup(),
        "slot", VarHandle.class, Opaque.class, Object.class);
    static final VarHandle READ = fieldVarHandle(MethodHandles.lookup(),
        "readCounter", VarHandle.class, Opaque.class, long.class);

    volatile @Nullable Object slot = "old";
    volatile long readCounter;

    @Actor
    public void consumer() {
      SLOT.setRelease(this, null);
      READ.setOpaque(this, 1);
    }

    @Actor
    public void producer(II_Result r) {
      if ((long) READ.getVolatile(this) == 1) {
        SLOT.setRelease(this, "new");
        r.r1 = 1;
      } else {
        r.r1 = 0;
      }
    }

    @Arbiter
    public void arbiter(II_Result r) {
      r.r2 = (SLOT.getVolatile(this) == null) ? 0 : 1;
    }
  }
}
