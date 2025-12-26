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

import static com.github.benmanes.caffeine.cache.Buffer.FAILED;
import static com.github.benmanes.caffeine.cache.Buffer.FULL;
import static com.github.benmanes.caffeine.cache.Buffer.SUCCESS;
import static com.github.benmanes.caffeine.cache.StripedBuffer.MAXIMUM_TABLE_SIZE;
import static com.github.benmanes.caffeine.cache.StripedBuffer.NCPU;
import static com.github.benmanes.caffeine.cache.StripedBuffer.findVarHandle;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.mockito.Mockito;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.Var;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("ClassEscapesDefinedScope")
public final class StripedBufferTest {
  static final Integer ELEMENT = 1;

  @Test(dataProvider = "buffers")
  public void init_null(FakeBuffer<Integer> buffer) {
    assertThat(buffer.table).isNull();

    var result = buffer.offer(ELEMENT);
    assertThat(buffer.table).hasLength(1);
    assertThat(result).isEqualTo(SUCCESS);
  }

  @Test(dataProvider = "buffers")
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void init_empty(FakeBuffer<Integer> buffer) {
    buffer.table = new Buffer[0];

    var result = buffer.offer(ELEMENT);
    assertThat(buffer.table).hasLength(1);
    assertThat(result).isEqualTo(SUCCESS);
  }

  @Test
  public void expand() {
    var buffer = new FakeBuffer<Integer>(FAILED);
    assertThat(buffer.offer(ELEMENT)).isEqualTo(SUCCESS);

    @Var var success = false;
    for (int i = 0; i < 64; i++) {
      int result = buffer.offer(ELEMENT);
      success |= (result == SUCCESS);
    }
    assertThat(success).isTrue();
    assertThat(buffer.reads()).isEqualTo(0);
    assertThat(buffer.writes()).isGreaterThan(1);
    assertThat(buffer.table).asList().contains(null);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void expand_exceeds() {
    var striped = new FakeBuffer<>(FAILED);
    var buffer = Mockito.mock(Buffer.class);
    when(buffer.offer(any())).thenReturn(FAILED);
    var table = new Buffer[2 * MAXIMUM_TABLE_SIZE];
    Arrays.fill(table, buffer);
    striped.table = table;

    assertThat(striped.expandOrRetry(ELEMENT, 0, 1, /* wasUncontended= */ true)).isEqualTo(FAILED);
  }

  @Test
  @SuppressWarnings("ThreadPriorityCheck")
  public void expand_concurrent() {
    var buffer = new FakeBuffer<Boolean>(FAILED);
    ConcurrentTestHarness.timeTasks(10 * NCPU, () -> {
      for (int i = 0; i < 1000; i++) {
        assertThat(buffer.offer(true)).isAnyOf(SUCCESS, FULL, FAILED);
        Thread.yield();
      }
    });
    assertThat(buffer.table).hasLength(MAXIMUM_TABLE_SIZE);
  }

  @Test(dataProvider = "buffers")
  @SuppressWarnings("ThreadPriorityCheck")
  public void produce(FakeBuffer<Integer> buffer) {
    ConcurrentTestHarness.timeTasks(NCPU, () -> {
      for (int i = 0; i < 10; i++) {
        assertThat(buffer.offer(ELEMENT)).isAnyOf(SUCCESS, FULL, FAILED);
        Thread.yield();
      }
    });
    assertThat(buffer.table).isNotNull();
    assertThat(requireNonNull(buffer.table).length).isAtMost(MAXIMUM_TABLE_SIZE);
  }

  @Test(dataProvider = "buffers")
  public void drain(FakeBuffer<Integer> buffer) {
    buffer.drainTo(e -> {});
    assertThat(buffer.drains).isEqualTo(0);

    // Expand and drain
    assertThat(buffer.offer(ELEMENT)).isEqualTo(SUCCESS);

    buffer.drainTo(e -> {});
    assertThat(buffer.drains).isEqualTo(1);
  }

  @Test
  public void counts() {
    var buffer = new FakeBuffer<Integer>(SUCCESS);
    assertThat(buffer.writes()).isEqualTo(0);
    assertThat(buffer.reads()).isEqualTo(0);
    assertThat(buffer.size()).isEqualTo(0);

    for (int i = 0; i < 64; i++) {
      assertThat(buffer.offer(ELEMENT)).isEqualTo(SUCCESS);
    }
    assertThat(buffer.writes()).isEqualTo(64);
    assertThat(buffer.reads()).isEqualTo(0);
    assertThat(buffer.size()).isEqualTo(64);
    buffer.drainTo(e -> {});
    assertThat(buffer.reads()).isEqualTo(64);
    assertThat(buffer.size()).isEqualTo(0);
  }

  @Test
  public void findVarHandle_absent() {
    assertThrows(ExceptionInInitializerError.class, () ->
        findVarHandle(StripedBuffer.class, "absent", int.class));
  }

  @DataProvider(name = "buffers")
  public Object[] providesBuffers() {
    var results = List.of(SUCCESS, FAILED, FULL);
    var buffers = new ArrayList<Buffer<Integer>>();
    for (var result : results) {
      buffers.add(new FakeBuffer<>(result));
    }
    return buffers.toArray();
  }

  static final class FakeBuffer<E> extends StripedBuffer<E> {
    final int result;

    int drains;
    int writes;
    int reads;

    FakeBuffer(int result) {
      this.result = result;
      this.writes = 1;
    }

    @Override protected Buffer<E> create(E e) {
      return new Buffer<>() {
        @Override public int offer(E e) {
          if (result == SUCCESS) {
            writes++;
          }
          return result;
        }
        @Override public void drainTo(Consumer<E> consumer) {
          reads = writes;
          drains++;
        }
        @Override public long reads() {
          return reads;
        }
        @Override public long writes() {
          return writes;
        }
      };
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).addValue(result).toString();
    }
  }
}
