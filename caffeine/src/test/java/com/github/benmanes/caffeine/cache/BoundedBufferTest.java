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

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

/**
 * The tests cases for the {@link BoundedBuffer}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class BoundedBufferTest {
  static final String DUMMY = "test";

  @DataProvider
  public Object[][] buffer() {
    return new Object[][] {{ new BoundedBuffer<String>() }};
  }

  @Test(dataProvider = "buffer")
  public void offer(BoundedBuffer<String> buffer) {
    ConcurrentTestHarness.timeTasks(10, () -> {
      for (int i = 0; i < 100; i++) {
        buffer.offer(DUMMY);
      }
    });
    assertThat(buffer.writes()).isGreaterThan(0);
    assertThat(buffer.writes()).isEqualTo(buffer.size());
  }

  @Test(dataProvider = "buffer")
  public void drain(BoundedBuffer<String> buffer) {
    for (int i = 0; i < BoundedBuffer.BUFFER_SIZE; i++) {
      buffer.offer(DUMMY);
    }
    long[] read = new long[1];
    buffer.drainTo(e -> read[0]++);
    assertThat(read[0]).isEqualTo(buffer.reads());
    assertThat(read[0]).isEqualTo(buffer.writes());
  }

  @Test(dataProvider = "buffer")
  @SuppressWarnings("ThreadPriorityCheck")
  public void offerAndDrain(BoundedBuffer<String> buffer) {
    var lock = new ReentrantLock();
    var reads = new AtomicInteger();
    ConcurrentTestHarness.timeTasks(10, () -> {
      for (int i = 0; i < 1000; i++) {
        boolean shouldDrain = (buffer.offer(DUMMY) == Buffer.FULL);
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
}
