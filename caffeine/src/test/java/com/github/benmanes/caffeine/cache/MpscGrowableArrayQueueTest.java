/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.BaseMpscLinkedArrayQueue.findVarHandle;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressFBWarnings("SEC_SIDE_EFFECT_CONSTRUCTOR")
@SuppressWarnings({"ClassEscapesDefinedScope", "PMD.LooseCoupling"})
final class MpscGrowableArrayQueueTest {
  private static final int NUM_PRODUCERS = 10;
  private static final int PRODUCE = 100;

  private static final int POPULATED_SIZE = 10;
  private static final int FULL_SIZE = 32;

  /* --------------- Constructor --------------- */

  @Test
  void constructor_initialCapacity_tooSmall() {
    assertThrows(IllegalArgumentException.class, () ->
        new MpscGrowableArrayQueue<Integer>(/* initialCapacity= */ 1, /* maxCapacity= */ 4));
  }

  @Test
  void constructor_maxCapacity_tooSmall() {
    assertThrows(IllegalArgumentException.class, () ->
        new MpscGrowableArrayQueue<Integer>(/* initialCapacity= */ 4, /* maxCapacity= */ 1));
  }

  @Test
  void constructor_inverted() {
    assertThrows(IllegalArgumentException.class, () ->
        new MpscGrowableArrayQueue<Integer>(/* initialCapacity= */ 8, /* maxCapacity= */ 4));
  }

  @Test
  void constructor() {
    var queue = new MpscGrowableArrayQueue<Integer>(/* initialCapacity= */ 4, /* maxCapacity= */ 8);
    assertThat(queue.capacity()).isEqualTo(8);
  }

  /* --------------- Size --------------- */

  @Test
  void size_whenEmpty() {
    var queue = makePopulated(0);
    assertThat(queue.size()).isEqualTo(0);
  }

  @Test
  void size_whenPopulated() {
    var queue = makePopulated(POPULATED_SIZE);
    assertThat(queue.size()).isEqualTo(POPULATED_SIZE);
  }

  /* --------------- Offer --------------- */

  @Test
  void offer_whenEmpty() {
    var queue = makePopulated(0);
    assertThat(queue.offer(1)).isTrue();
    assertThat(queue).hasSize(1);
  }

  @Test
  void offer_whenPopulated() {
    var queue = makePopulated(POPULATED_SIZE);
    assertThat(queue.offer(1)).isTrue();
    assertThat(queue).hasSize(POPULATED_SIZE + 1);
  }

  @Test
  void offer_whenFull() {
    var queue = makePopulated(FULL_SIZE);
    assertThat(queue.offer(1)).isFalse();
    assertThat(queue).hasSize(FULL_SIZE);
  }

  @Test
  void relaxedOffer_whenEmpty() {
    var queue = makePopulated(0);
    assertThat(queue.relaxedOffer(1)).isTrue();
    assertThat(queue).hasSize(1);
  }

  @Test
  void relaxedOffer_whenPopulated() {
    var queue = makePopulated(POPULATED_SIZE);
    assertThat(queue.relaxedOffer(1)).isTrue();
    assertThat(queue).hasSize(POPULATED_SIZE + 1);
  }

  @Test
  void relaxedOffer_whenFull() {
    var queue = makePopulated(FULL_SIZE);
    assertThat(queue.relaxedOffer(1)).isFalse();
    assertThat(queue).hasSize(FULL_SIZE);
  }

  /* --------------- Poll --------------- */

  @Test
  void poll_whenEmpty() {
    var queue = makePopulated(0);
    assertThat(queue.poll()).isNull();
  }

  @Test
  void poll_whenPopulated() {
    var queue = makePopulated(POPULATED_SIZE);
    assertThat(queue.poll()).isNotNull();
    assertThat(queue).hasSize(POPULATED_SIZE - 1);
  }

  @Test
  @SuppressWarnings("StatementWithEmptyBody")
  void poll_toEmpty() {
    var queue = makePopulated(FULL_SIZE);
    while (queue.poll() != null) { /* consume */ }
    assertThat(queue).isEmpty();
  }

  @Test
  void relaxedPoll_whenEmpty() {
    var queue = makePopulated(0);
    assertThat(queue.relaxedPoll()).isNull();
  }

  @Test
  void relaxedPoll_whenPopulated() {
    var queue = makePopulated(POPULATED_SIZE);
    assertThat(queue.relaxedPoll()).isNotNull();
    assertThat(queue).hasSize(POPULATED_SIZE - 1);
  }

  @Test
  @SuppressWarnings("StatementWithEmptyBody")
  void relaxedPoll_toEmpty() {
    var queue = makePopulated(FULL_SIZE);
    while (queue.relaxedPoll() != null) { /* consume */ }
    assertThat(queue).isEmpty();
  }

  /* --------------- Peek --------------- */

  @Test
  void peek_whenEmpty() {
    var queue = makePopulated(0);
    assertThat(queue.peek()).isNull();
  }

  @Test
  void peek_whenPopulated() {
    var queue = makePopulated(POPULATED_SIZE);
    assertThat(queue.peek()).isNotNull();
    assertThat(queue).hasSize(POPULATED_SIZE);
  }

  @Test
  void peek_toEmpty() {
    var queue = makePopulated(FULL_SIZE);
    for (int i = 0; i < FULL_SIZE; i++) {
      assertThat(queue.peek()).isNotNull();
      var item = queue.poll();
      assertThat(item).isNotNull();
    }
    assertThat(queue.peek()).isNull();
  }

  @Test
  void relaxedPeek_whenEmpty() {
    var queue = makePopulated(0);
    assertThat(queue.relaxedPeek()).isNull();
  }

  @Test
  void relaxedPeek_whenPopulated() {
    var queue = makePopulated(POPULATED_SIZE);
    assertThat(queue.relaxedPeek()).isNotNull();
    assertThat(queue).hasSize(POPULATED_SIZE);
  }

  @Test
  void relaxedPeek_toEmpty() {
    var queue = makePopulated(FULL_SIZE);
    for (int i = 0; i < FULL_SIZE; i++) {
      assertThat(queue.relaxedPeek()).isNotNull();
      var item = queue.poll();
      assertThat(item).isNotNull();
    }
    assertThat(queue.relaxedPeek()).isNull();
  }

  /* --------------- Miscellaneous --------------- */

  @Test
  void iterator() {
    var queue = makePopulated(FULL_SIZE);
    assertThrows(UnsupportedOperationException.class, queue::iterator);
  }

  @Test
  void inspection() {
    var queue = makePopulated(POPULATED_SIZE);
    assertThat(queue.currentConsumerIndex()).isEqualTo(0);
    assertThat(queue.currentProducerIndex()).isEqualTo(POPULATED_SIZE);
  }

  @Test
  void getNextBufferSize_invali() {
    var queue = makePopulated(FULL_SIZE);
    var buffer = new Integer[FULL_SIZE + 1];
    assertThrows(IllegalStateException.class, () -> queue.getNextBufferSize(buffer));
  }

  @Test
  void toString_nondescript() {
    var empty = makePopulated(0);
    var full = makePopulated(FULL_SIZE);
    assertThat(empty.toString()).contains(empty.getClass().getSimpleName());
    assertThat(full.toString()).contains(empty.getClass().getSimpleName());
  }

  @Test
  void findVarHandle_absent() {
    assertThrows(ExceptionInInitializerError.class, () ->
        findVarHandle(BaseMpscLinkedArrayQueueProducerFields.class, "absent", int.class));
  }

  /* --------------- Concurrency --------------- */

  @Test
  @SuppressWarnings("StatementWithEmptyBody")
  void oneProducer_oneConsumer() {
    var queue = makePopulated(0);
    var started = new AtomicInteger();
    var finished = new AtomicInteger();

    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAsserted(() -> assertThat(started.get()).isEqualTo(2));
      for (int i = 0; i < PRODUCE; i++) {
        while (!queue.offer(i)) { /* produce */ }
      }
      finished.incrementAndGet();
    });
    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAsserted(() -> assertThat(started.get()).isEqualTo(2));
      for (int i = 0; i < PRODUCE; i++) {
        while (queue.poll() == null) { /* consume */ }
      }
      finished.incrementAndGet();
    });

    await().untilAsserted(() -> assertThat(finished.get()).isEqualTo(2));
    assertThat(queue).isEmpty();
  }

  @Test
  void manyProducers_noConsumer() {
    var queue = makePopulated(0);
    var count = new AtomicInteger();
    ConcurrentTestHarness.timeTasks(NUM_PRODUCERS, () -> {
      for (int i = 0; i < PRODUCE; i++) {
        if (queue.offer(i)) {
          count.incrementAndGet();
        }
      }
    });
    assertThat(queue).hasSize(count.get());
  }

  @Test
  @SuppressWarnings("StatementWithEmptyBody")
  void manyProducers_oneConsumer() {
    var queue = makePopulated(0);
    var started = new AtomicInteger();
    var finished = new AtomicInteger();

    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAsserted(() -> assertThat(started.get()).isEqualTo(NUM_PRODUCERS + 1));
      for (int i = 0; i < (NUM_PRODUCERS * PRODUCE); i++) {
        while (queue.poll() == null) { /* consume */ }
      }
      finished.incrementAndGet();
    });

    ConcurrentTestHarness.timeTasks(NUM_PRODUCERS, () -> {
      started.incrementAndGet();
      await().untilAsserted(() -> assertThat(started.get()).isEqualTo(NUM_PRODUCERS + 1));
      for (int i = 0; i < PRODUCE; i++) {
        while (!queue.offer(i)) { /* produce */ }
      }
      finished.incrementAndGet();
    });

    await().untilAsserted(() -> assertThat(finished.get()).isEqualTo(NUM_PRODUCERS + 1));
    assertThat(queue).isEmpty();
  }

  @Test
  void offer_unknownResult() {
    var queue = new MpscGrowableArrayQueue<Integer>(2, 8) {
      @Override protected int offerSlowPath(long mask, long pIndex, long producerLimit) {
        return -1;
      }
    };
    queue.producerLimit = 0;
    assertThat(queue.offer(1)).isTrue();
    assertThat(queue.poll()).isEqualTo(1);
  }

  @Test
  void offer_resizeFails() {
    var queue = new MpscGrowableArrayQueue<Integer>(2, 8) {
      @Override protected long availableInQueue(long pIndex, long cIndex) {
        return -1;
      }
      @Override protected int offerSlowPath(long mask, long pIndex, long producerLimit) {
        return 3;
      }
    };
    queue.producerLimit = 0;
    assertThrows(IllegalStateException.class, () -> queue.offer(1));
  }

  private static MpscGrowableArrayQueue<Integer> makePopulated(int items) {
    var queue = new MpscGrowableArrayQueue<Integer>(4, FULL_SIZE);
    for (int i = 0; i < items; i++) {
      queue.add(i);
    }
    return queue;
  }
}
