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

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.google.common.truth.Truth.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MpscGrowableArrayQueueTest {
  private static final int NUM_PRODUCERS = 10;
  private static final int PRODUCE = 100;

  private static final int POPULATED_SIZE = 10;
  private static final int FULL_SIZE = 32;

  /* --------------- Constructor --------------- */

  @Test
  public void constructor_initialCapacity_tooSmall() {
    assertThrows(IllegalArgumentException.class, () ->
        new MpscGrowableArrayQueue<Integer>(/* initialCapacity */ 1, /* maxCapacity */ 4));
  }

  @Test
  public void constructor_maxCapacity_tooSmall() {
    assertThrows(IllegalArgumentException.class, () ->
        new MpscGrowableArrayQueue<Integer>(/* initialCapacity */ 4, /* maxCapacity */ 1));
  }

  @Test
  public void constructor_inverted() {
    assertThrows(IllegalArgumentException.class, () ->
        new MpscGrowableArrayQueue<Integer>(/* initialCapacity */ 8, /* maxCapacity */ 4));
  }

  @Test
  public void constructor() {
    var queue = new MpscGrowableArrayQueue<Integer>(/* initialCapacity */ 4, /* maxCapacity */ 8);
    assertThat(queue.capacity()).isEqualTo(8);
  }

  /* --------------- Size --------------- */

  @Test(dataProvider = "empty")
  public void size_whenEmpty(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.size()).isEqualTo(0);
  }

  @Test(dataProvider = "populated")
  public void size_whenPopulated(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.size()).isEqualTo(POPULATED_SIZE);
  }

  /* --------------- Offer --------------- */

  @Test(dataProvider = "empty")
  public void offer_whenEmpty(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.offer(1)).isTrue();
    assertThat(queue).hasSize(1);
  }

  @Test(dataProvider = "populated")
  public void offer_whenPopulated(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.offer(1)).isTrue();
    assertThat(queue).hasSize(POPULATED_SIZE + 1);
  }

  @Test(dataProvider = "full")
  public void offer_whenFull(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.offer(1)).isFalse();
    assertThat(queue).hasSize(FULL_SIZE);
  }

  @Test(dataProvider = "empty")
  public void relaxedOffer_whenEmpty(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.relaxedOffer(1)).isTrue();
    assertThat(queue).hasSize(1);
  }

  @Test(dataProvider = "populated")
  public void relaxedOffer_whenPopulated(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.relaxedOffer(1)).isTrue();
    assertThat(queue).hasSize(POPULATED_SIZE + 1);
  }

  @Test(dataProvider = "full")
  public void relaxedOffer_whenFull(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.relaxedOffer(1)).isFalse();
    assertThat(queue).hasSize(FULL_SIZE);
  }

  /* --------------- Poll --------------- */

  @Test(dataProvider = "empty")
  public void poll_whenEmpty(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.poll()).isNull();
  }

  @Test(dataProvider = "populated")
  public void poll_whenPopulated(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.poll()).isNotNull();
    assertThat(queue).hasSize(POPULATED_SIZE - 1);
  }

  @Test(dataProvider = "full")
  public void poll_toEmpty(MpscGrowableArrayQueue<Integer> queue) {
    while (queue.poll() != null) {}
    assertThat(queue).isEmpty();
  }

  @Test(dataProvider = "empty")
  public void relaxedPoll_whenEmpty(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.relaxedPoll()).isNull();
  }

  @Test(dataProvider = "populated")
  public void relaxedPoll_whenPopulated(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.relaxedPoll()).isNotNull();
    assertThat(queue).hasSize(POPULATED_SIZE - 1);
  }

  @Test(dataProvider = "full")
  public void relaxedPoll_toEmpty(MpscGrowableArrayQueue<Integer> queue) {
    while (queue.relaxedPoll() != null) {}
    assertThat(queue).isEmpty();
  }

  /* --------------- Peek --------------- */

  @Test(dataProvider = "empty")
  public void peek_whenEmpty(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.peek()).isNull();
  }

  @Test(dataProvider = "populated")
  public void peek_whenPopulated(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.peek()).isNotNull();
    assertThat(queue).hasSize(POPULATED_SIZE);
  }

  @Test(dataProvider = "full")
  public void peek_toEmpty(MpscGrowableArrayQueue<Integer> queue) {
    for (int i = 0; i < FULL_SIZE; i++) {
      assertThat(queue.peek()).isNotNull();
      var item = queue.poll();
      assertThat(item).isNotNull();
    }
    assertThat(queue.peek()).isNull();
  }

  @Test(dataProvider = "empty")
  public void relaxedPeek_whenEmpty(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.relaxedPeek()).isNull();
  }

  @Test(dataProvider = "populated")
  public void relaxedPeek_whenPopulated(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.relaxedPeek()).isNotNull();
    assertThat(queue).hasSize(POPULATED_SIZE);
  }

  @Test(dataProvider = "full")
  public void relaxedPeek_toEmpty(MpscGrowableArrayQueue<Integer> queue) {
    for (int i = 0; i < FULL_SIZE; i++) {
      assertThat(queue.relaxedPeek()).isNotNull();
      var item = queue.poll();
      assertThat(item).isNotNull();
    }
    assertThat(queue.relaxedPeek()).isNull();
  }

  /* --------------- Miscellaneous --------------- */

  @Test(dataProvider = "full")
  public void iterator(MpscGrowableArrayQueue<Integer> queue) {
    assertThrows(UnsupportedOperationException.class, queue::iterator);
  }

  @Test(dataProvider = "populated")
  public void inspection(MpscGrowableArrayQueue<Integer> queue) {
    assertThat(queue.currentConsumerIndex()).isEqualTo(0);
    assertThat(queue.currentProducerIndex()).isEqualTo(POPULATED_SIZE);
  }

  @Test(dataProvider = "full")
  public void getNextBufferSize_invalid(MpscGrowableArrayQueue<Integer> queue) {
    var buffer = new Integer[FULL_SIZE + 1];
    assertThrows(IllegalStateException.class, () -> queue.getNextBufferSize(buffer));
  }

  /* --------------- Concurrency --------------- */

  @Test(dataProvider = "empty")
  public void oneProducer_oneConsumer(MpscGrowableArrayQueue<Integer> queue) {
    var started = new AtomicInteger();
    var finished = new AtomicInteger();

    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(2));
      for (int i = 0; i < PRODUCE; i++) {
        while (!queue.offer(i)) {}
      }
      finished.incrementAndGet();
    });
    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(2));
      for (int i = 0; i < PRODUCE; i++) {
        while (queue.poll() == null) {}
      }
      finished.incrementAndGet();
    });

    await().untilAtomic(finished, is(2));
    assertThat(queue).isEmpty();
  }

  @Test(dataProvider = "empty")
  public void manyProducers_noConsumer(MpscGrowableArrayQueue<Integer> queue) {
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

  @Test(dataProvider = "empty")
  public void manyProducers_oneConsumer(MpscGrowableArrayQueue<Integer> queue) {
    var started = new AtomicInteger();
    var finished = new AtomicInteger();

    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(NUM_PRODUCERS + 1));
      for (int i = 0; i < (NUM_PRODUCERS * PRODUCE); i++) {
        while (queue.poll() == null) {}
      }
      finished.incrementAndGet();
    });

    ConcurrentTestHarness.timeTasks(NUM_PRODUCERS, () -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(NUM_PRODUCERS + 1));
      for (int i = 0; i < PRODUCE; i++) {
        while (!queue.offer(i)) {}
      }
      finished.incrementAndGet();
    });

    await().untilAtomic(finished, is(NUM_PRODUCERS + 1));
    assertThat(queue).isEmpty();
  }

  /* --------------- Providers --------------- */

  @DataProvider(name = "empty")
  public Object[][] providesEmpty() {
    return new Object[][] {{ makePopulated(0) }};
  }

  @DataProvider(name = "populated")
  public Object[][] providesPopulated() {
    return new Object[][] {{ makePopulated(POPULATED_SIZE) }};
  }

  @DataProvider(name = "full")
  public Object[][] providesFull() {
    return new Object[][] {{ makePopulated(FULL_SIZE) }};
  }

  static MpscGrowableArrayQueue<Integer> makePopulated(int items) {
    var queue = new MpscGrowableArrayQueue<Integer>(4, FULL_SIZE);
    for (int i = 0; i < items; i++) {
      queue.add(i);
    }
    return queue;
  }
}
