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
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntBiFunction;

import org.jetbrains.lincheck.Lincheck;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.BBHeader.ReadAndWriteCounterRef;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

/**
 * The tests cases for the {@link BoundedBuffer}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ClassEscapesDefinedScope")
public final class BoundedBufferTest {

  @DataProvider
  public Object[][] buffer() {
    return new Object[][] {{ new BoundedBuffer<Boolean>() }};
  }

  @Test(dataProvider = "buffer")
  public void offer(BoundedBuffer<Boolean> buffer) {
    ConcurrentTestHarness.timeTasks(10, () -> {
      for (int i = 0; i < 100; i++) {
        assertThat(buffer.offer(true)).isAnyOf(SUCCESS, FULL, FAILED);
      }
    });
    assertThat(buffer.writes()).isGreaterThan(0);
    assertThat(buffer.writes()).isEqualTo(buffer.size());
  }

  @Test(dataProvider = "buffer")
  public void drain(BoundedBuffer<Boolean> buffer) {
    for (int i = 0; i < BoundedBuffer.BUFFER_SIZE; i++) {
      assertThat(buffer.offer(true)).isAnyOf(SUCCESS, FULL);
    }
    long[] read = new long[1];
    buffer.drainTo(e -> read[0]++);
    assertThat(read[0]).isEqualTo(buffer.reads());
    assertThat(read[0]).isEqualTo(buffer.writes());
  }

  @Test(dataProvider = "buffer")
  @SuppressWarnings("ThreadPriorityCheck")
  public void offerAndDrain(BoundedBuffer<Boolean> buffer) {
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
  public void overflow() {
    @SuppressWarnings("NullAway")
    var buffer = new BoundedBuffer.RingBuffer<Boolean>(null);
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

  @Test(groups = "lincheck")
  public void concurrent_offer() {
    Lincheck.runConcurrentTest(() -> {
      try {
        int reads = 32;
        var buffer = new BoundedBuffer<Integer>();
        Runnable task = () -> {
          for (int i = 0; i < reads; i++) {
            int result = buffer.offer(i);
            assertTrue((result == SUCCESS) || (result == FAILED) || (result == FULL));
          }
        };
        var offer1 = new Thread(task);
        var offer2 = new Thread(task);
        offer1.start();
        offer2.start();
        offer1.join();
        offer2.join();
        assertTrue(buffer.size() >= BoundedBuffer.BUFFER_SIZE, () -> "Observed: " + buffer.size());
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test(groups = "lincheck")
  public void concurrent_drain_offer() {
    checkConcurrent(BoundedBuffer::offer);
  }

  @Test(groups = "lincheck")
  public void concurrent_drain_expandOrRetry() {
    checkConcurrent((buffer, e) -> buffer.expandOrRetry(e, e, e, /* wasUncontended= */ false));
  }

  private static void checkConcurrent(ToIntBiFunction<BoundedBuffer<Integer>, Integer> writer) {
    Lincheck.runConcurrentTest(() -> {
      try {
        int reads = 32;
        var drained = new AtomicInteger();
        var buffer = new BoundedBuffer<Integer>();
        var offer = new Thread(() -> {
          for (int i = 0; i < reads; i++) {
            int result = writer.applyAsInt(buffer, i);
            assertTrue((result == SUCCESS) || (result == FAILED) || (result == FULL));
          }
        });
        var drain = new Thread(() -> buffer.drainTo(e -> drained.incrementAndGet()));
        offer.start();
        drain.start();
        offer.join();
        drain.join();
        int result = drained.get();
        assertTrue(result <= reads, () -> "Observed: " + result);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    });
  }

  @Test
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  public void reflectivelyConstruct() throws ReflectiveOperationException {
    var constructor = BBHeader.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Test
  public void findVarHandle_absent() {
    assertThrows(ExceptionInInitializerError.class, () ->
        findVarHandle(ReadAndWriteCounterRef.class, "absent", int.class));
  }
}
