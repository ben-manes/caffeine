/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import org.jctools.util.UnsafeAccess;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * A comparison of different lookup approaches for indexes for a slot in a fixed-sized shared array.
 * This approach is used for elimination (threads backoff and rendezvous) and striping (reduced
 * contention when recording an update).
 * <p>
 * The obvious approach is to store a slot in a ThreadLocal, which is a hashmap stored within a
 * {@link java.lang.Thread} object. The current implementation has a poor ops/s, making it a poor
 * choice if accessed frequently in tight code.
 * <p>
 * The next approach might be to use a table lookup keyed by the thread's id or the thread
 * instance's hashCode. A trick used by Java's concurrent adders is to use the thread local
 * random's probe and update it after each usage to avoid pinning to a slot. These approaches try
 * to be fast and provide a good distribution. However, according to the jemalloc paper a
 * round-robin assignment provides the best load balancing strategy.
 * <p>
 * A round-robin assignment requires a mapping of the thread's id to its slot. This can be
 * implemented multiple ways such as using binary search, a hash table, or a sparse array.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class SlotLookupBenchmark {
  static final int SPARSE_SIZE = 2 << 14;
  static final int ARENA_SIZE = 2 << 6;
  static final VarHandle PROBE;

  ThreadLocal<Integer> threadLocal;
  long element;
  long[] array;

  long probeOffset;

  long index;
  Long2IntMap mapping;

  int[] sparse;

  @Setup
  public void setupThreadLocal() {
    threadLocal = ThreadLocal.withInitial(() -> {
      for (int i = 0; i < ARENA_SIZE; i++) {
        // Populates the internal hashmap to emulate other thread local usages
        ThreadLocal.withInitial(Thread.currentThread()::getId);
      }
      return selectSlot(ThreadLocalRandom.current().nextInt());
    });
  }

  @Setup
  public void setupBinarySearch() {
    array = new long[ARENA_SIZE];
    element = ThreadLocalRandom.current().nextLong(ARENA_SIZE);
    for (int i = 0; i < ARENA_SIZE; i++) {
      array[i] = selectSlot(i);
    }
    Arrays.sort(array);
  }

  @Setup
  public void setupStriped64() {
    probeOffset = UnsafeAccess.fieldOffset(Thread.class, "threadLocalRandomProbe");
  }

  @Setup
  public void setupHashing() {
    index = ThreadLocalRandom.current().nextInt(ARENA_SIZE);
    mapping = new Long2IntOpenHashMap(ARENA_SIZE);
    for (int i = 0; i < ARENA_SIZE; i++) {
      mapping.put(i, selectSlot(i));
    }
  }

  @Setup
  public void setupSparseArray() {
    sparse = new int[SPARSE_SIZE];
    for (int i = 0; i < SPARSE_SIZE; i++) {
      sparse[i] = selectSlot(i);
    }
  }

  @Benchmark
  public int threadLocal() {
    // Emulates holding the arena slot in a thread-local
    return threadLocal.get();
  }

  @Benchmark
  public int binarySearch() {
    // Emulates finding the arena slot by a COW mapping of thread ids
    return Arrays.binarySearch(array, element);
  }

  @Benchmark
  public int hashing() {
    // Emulates finding the arena slot by a COW mapping the thread id to a slot index
    return mapping.get(index);
  }

  @Benchmark
  public int sparseArray() {
    // Emulates having a COW sparse array mapping the thread id to a slot location
    return sparse[(int) Thread.currentThread().getId()];
  }

  @Benchmark
  public int threadIdHash() {
    // Emulates finding the arena slot by hashing the thread id
    long id = Thread.currentThread().getId();
    int hash = (((int) (id ^ (id >>> 32))) ^ 0x811c9dc5) * 0x01000193;
    return selectSlot(hash);
  }

  @Benchmark
  public int threadHashCode() {
    // Emulates finding the arena slot by the thread's hashCode
    long id = Thread.currentThread().hashCode();
    int hash = (((int) (id ^ (id >>> 32))) ^ 0x811c9dc5) * 0x01000193;
    return selectSlot(hash);
  }

  @Benchmark
  public long striped64_unsafe(Blackhole blackhole) {
    // Emulates finding the arena slot by reusing the thread-local random seed (j.u.c.a.Striped64)
    int hash = getProbe_unsafe();
    if (hash == 0) {
      blackhole.consume(ThreadLocalRandom.current()); // force initialization
      hash = getProbe_unsafe();
    }
    advanceProbe_unsafe(hash);
    int index = selectSlot(hash);
    return array[index];
  }

  private int getProbe_unsafe() {
    return UnsafeAccess.UNSAFE.getInt(Thread.currentThread(), probeOffset);
  }

  private void advanceProbe_unsafe(int probe) {
    probe ^= probe << 13; // xorshift
    probe ^= probe >>> 17;
    probe ^= probe << 5;
    UnsafeAccess.UNSAFE.putInt(Thread.currentThread(), probeOffset, probe);
  }

  @Benchmark
  public long striped64_varHandle(Blackhole blackhole) {
    // Emulates finding the arena slot by reusing the thread-local random seed (j.u.c.a.Striped64)
    int hash = getProbe_varHandle();
    if (hash == 0) {
      blackhole.consume(ThreadLocalRandom.current()); // force initialization
      hash = getProbe_varHandle();
    }
    advanceProbe_varHandle(hash);
    int index = selectSlot(hash);
    return array[index];
  }

  private int getProbe_varHandle() {
    return (int) PROBE.get(Thread.currentThread());
  }

  private void advanceProbe_varHandle(int probe) {
    probe ^= probe << 13; // xorshift
    probe ^= probe >>> 17;
    probe ^= probe << 5;
    PROBE.set(Thread.currentThread(), probe);
  }

  private static int selectSlot(int i) {
    return i & (ARENA_SIZE - 1);
  }

  static {
    try {
      PROBE = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup())
          .findVarHandle(Thread.class, "threadLocalRandomProbe", int.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
