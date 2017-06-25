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

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import com.github.benmanes.caffeine.cache.buffer.BufferType;

/**
 * A concurrent benchmark for read buffer implementation options. A read buffer may be a lossy,
 * bounded, non-blocking, multiple-producer / single-consumer unordered queue. These buffers are
 * used to record a memento of the read operation that occurred on the cache, which is later
 * replayed by a single thread on the eviction policy to predict the optimal victim when an entry
 * must be removed.
 * <p>
 * The implementations are per-segment buffers. In practice the unordered characteristic allows
 * multiple buffers to be used, chosen by a strategy such as hashing on the thread id. A primary
 * goal of the buffer is to maximize read throughput of the cache.
 * <p>
 * The buffer should minimize garbage to manage its internal state, such as link nodes. This
 * optimization avoids additional garbage collection pauses that reduces overall throughput.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class ReadBufferBenchmark {

  @Param BufferType bufferType;
  ReadBuffer<Boolean> buffer;

  @AuxCounters
  @State(Scope.Thread)
  public static class RecordCounter {
    public int recordFailed;
    public int recordSuccess;
    public int recordFull;
  }

  @Setup
  public void setup() {
    buffer = bufferType.create();
  }

  @Benchmark @Group @GroupThreads(8)
  public void record(RecordCounter counters) {
    switch (buffer.offer(Boolean.TRUE)) {
      case ReadBuffer.FAILED:
        counters.recordFailed++;
        break;
      case ReadBuffer.SUCCESS:
        counters.recordSuccess++;
        break;
      case ReadBuffer.FULL:
        counters.recordFull++;
        break;
      default:
        throw new IllegalStateException();
    }
  }

  @Benchmark @Group @GroupThreads(1)
  public void drain() {
    buffer.drain();
  }
}
