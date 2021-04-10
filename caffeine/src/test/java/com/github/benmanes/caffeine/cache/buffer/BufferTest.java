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
package com.github.benmanes.caffeine.cache.buffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Arrays;
import java.util.Iterator;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.ReadBuffer;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

/**
 * The tests cases for a read buffer strategy. This validates an implementation approach which can
 * be benchmarked using the {@link com.github.benmanes.caffeine.cache.ReadBufferBenchmark}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class BufferTest {

  @DataProvider
  public Iterator<Object[]> buffers() {
    return Arrays.stream(BufferType.values())
        .map(factory -> new Object[] { factory.create() })
        .iterator();
  }

  @Test(dataProvider = "buffers")
  @SuppressWarnings("ThreadPriorityCheck")
  public void record(ReadBuffer<Boolean> buffer) {
    ConcurrentTestHarness.timeTasks(100, () -> {
      for (int i = 0; i < 1000; i++) {
        buffer.offer(Boolean.TRUE);
        Thread.yield();
      }
    });
    long recorded = buffer.recorded();
    assertThat(recorded, is((long) ReadBuffer.BUFFER_SIZE));
  }

  @Test(dataProvider = "buffers")
  public void drain(ReadBuffer<Boolean> buffer) {
    for (int i = 0; i < 2 * ReadBuffer.BUFFER_SIZE; i++) {
      buffer.offer(Boolean.TRUE);
    }
    buffer.drain();
    long drained = buffer.drained();
    long recorded = buffer.recorded();
    assertThat(drained, is(recorded));
  }

  @Test(dataProvider = "buffers")
  @SuppressWarnings("ThreadPriorityCheck")
  public void recordAndDrain(ReadBuffer<Boolean> buffer) {
    ConcurrentTestHarness.timeTasks(100, () -> {
      for (int i = 0; i < 1000; i++) {
        int result = buffer.offer(Boolean.TRUE);
        if (result == ReadBuffer.FULL) {
          buffer.drain();
        }
        Thread.yield();
      }
    });
    buffer.drain();
    long drained = buffer.drained();
    long recorded = buffer.recorded();
    assertThat(drained, is(recorded));
  }
}
