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

import static com.github.benmanes.caffeine.cache.BBHeader.ReadAndWriteCounterRef.findVarHandle;
import static com.github.benmanes.caffeine.cache.Buffer.FAILED;
import static com.github.benmanes.caffeine.cache.Buffer.FULL;
import static com.github.benmanes.caffeine.cache.Buffer.SUCCESS;
import static com.github.benmanes.caffeine.testing.Nullness.nullRef;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.BBHeader.ReadAndWriteCounterRef;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

/**
 * The tests cases for the {@link BoundedBuffer}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ClassEscapesDefinedScope")
final class BoundedBufferTest {

  @Test
  void offer() {
    var buffer = new BoundedBuffer<Boolean>();
    ConcurrentTestHarness.timeTasks(10, () -> {
      for (int i = 0; i < 100; i++) {
        assertThat(buffer.offer(true)).isAnyOf(SUCCESS, FULL, FAILED);
      }
    });
    assertThat(buffer.writes()).isGreaterThan(0);
    assertThat(buffer.writes()).isEqualTo(buffer.size());
  }

  @Test
  void drain() {
    var buffer = new BoundedBuffer<Boolean>();
    for (int i = 0; i < BoundedBuffer.BUFFER_SIZE; i++) {
      assertThat(buffer.offer(true)).isAnyOf(SUCCESS, FULL);
    }
    long[] read = new long[1];
    buffer.drainTo(e -> read[0]++);
    assertThat(read[0]).isEqualTo(buffer.reads());
    assertThat(read[0]).isEqualTo(buffer.writes());
  }

  @Test
  @SuppressWarnings("ThreadPriorityCheck")
  void offerAndDrain() {
    var buffer = new BoundedBuffer<Boolean>();
    var lock = new ReentrantLock();
    var reads = new AtomicInteger();
    ConcurrentTestHarness.timeTasks(10, () -> {
      for (int i = 0; i < 1000; i++) {
        boolean shouldDrain = (buffer.offer(true) == FULL);
        if (shouldDrain && lock.tryLock()) {
          buffer.drainTo(e -> reads.incrementAndGet());
          lock.unlock();
        }
        Thread.yield();
      }
    });
    buffer.drainTo(e -> reads.incrementAndGet());
    assertThat(reads.longValue()).isEqualTo(buffer.reads());
    assertThat(reads.longValue()).isEqualTo(buffer.writes());
  }

  @Test
  void overflow() {
    Boolean first = nullRef();
    var buffer = new BoundedBuffer.RingBuffer<>(first);
    buffer.writeCounter = Long.MAX_VALUE;
    buffer.readCounter = Long.MAX_VALUE;

    assertThat(buffer.offer(true)).isEqualTo(SUCCESS);
    var data = new ArrayList<>();
    buffer.drainTo(data::add);

    for (var e : buffer.buffer) {
      assertThat(e).isNull();
    }
    assertThat(data).containsExactly(true);
    assertThat(buffer.readCounter).isEqualTo(Long.MIN_VALUE);
    assertThat(buffer.writeCounter).isEqualTo(Long.MIN_VALUE);
  }

  @Test
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  void reflectivelyConstruct() throws ReflectiveOperationException {
    var constructor = BBHeader.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Test
  void findVarHandle_absent() {
    assertThrows(ExceptionInInitializerError.class, () ->
        findVarHandle(ReadAndWriteCounterRef.class, "absent", int.class));
  }
}
