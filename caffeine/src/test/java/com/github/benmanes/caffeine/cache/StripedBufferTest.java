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

import static com.github.benmanes.caffeine.cache.StripedBuffer.MAXIMUM_TABLE_SIZE;
import static com.github.benmanes.caffeine.cache.StripedBuffer.NCPU;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.base.MoreObjects;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class StripedBufferTest {
  static final Integer ELEMENT = 1;

  @Test(dataProvider = "buffers")
  public void init(FakeBuffer<Integer> buffer) {
    assertThat(buffer.table, is(nullValue()));

    buffer.offer(ELEMENT);
    assertThat(buffer.table.length, is(1));
  }

  @Test(dataProvider = "buffers")
  @SuppressWarnings("ThreadPriorityCheck")
  public void produce(FakeBuffer<Integer> buffer) {
    ConcurrentTestHarness.timeTasks(NCPU, () -> {
      for (int i = 0; i < 10; i++) {
        buffer.offer(ELEMENT);
        Thread.yield();
      }
    });
    assertThat(buffer.table.length, lessThanOrEqualTo(MAXIMUM_TABLE_SIZE));
  }

  @Test
  @SuppressWarnings("ThreadPriorityCheck")
  public void expand() {
    var buffer = new FakeBuffer<Boolean>(Buffer.FAILED);
    ConcurrentTestHarness.timeTasks(10 * NCPU, () -> {
      for (int i = 0; i < 1000; i++) {
        buffer.offer(Boolean.TRUE);
        Thread.yield();
      }
    });
    assertThat(buffer.table.length, is(MAXIMUM_TABLE_SIZE));
  }

  @Test(dataProvider = "buffers")
  public void drain(FakeBuffer<Integer> buffer) {
    buffer.drainTo(e -> {});
    assertThat(buffer.drains, is(0));

    // Expand and drain
    buffer.offer(ELEMENT);
    buffer.drainTo(e -> {});
    assertThat(buffer.drains, is(1));
  }

  @DataProvider(name = "buffers")
  public Object[] providesBuffers() {
    var results = List.of(Buffer.SUCCESS, Buffer.FAILED, Buffer.FULL);
    var buffers = new ArrayList<Buffer<Integer>>();
    for (var result : results) {
      buffers.add(new FakeBuffer<Integer>(result));
    }
    return buffers.toArray();
  }

  static final class FakeBuffer<E> extends StripedBuffer<E> {
    final int result;
    int drains = 0;

    FakeBuffer(int result) {
      this.result = result;
    }

    @Override protected Buffer<E> create(E e) {
      return new Buffer<E>() {
        @Override public int offer(E e) {
          return result;
        }
        @Override public void drainTo(Consumer<E> consumer) {
          drains++;
        }
        @Override public long size() {
          return 0L;
        }
        @Override public long reads() {
          return 0L;
        }
        @Override public long writes() {
          return 0L;
        }
      };
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).addValue(result).toString();
    }
  }
}
