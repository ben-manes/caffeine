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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

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

  /* --------------- Size --------------- */

  @Test(dataProvider = "empty")
  public void size_whenEmpty(MpscGrowableArrayQueue<Integer> buffer) {
    assertThat(buffer.size(), is(0));
  }

  @Test(dataProvider = "populated")
  public void size_whenPopulated(MpscGrowableArrayQueue<Integer> buffer) {
    assertThat(buffer.size(), is(POPULATED_SIZE));
  }

  /* --------------- Offer --------------- */

  @Test(dataProvider = "empty")
  public void offer_whenEmpty(MpscGrowableArrayQueue<Integer> buffer) {
    assertThat(buffer.offer(1), is(true));
    assertThat(buffer.size(), is(1));
  }

  @Test(dataProvider = "populated")
  public void offer_whenPopulated(MpscGrowableArrayQueue<Integer> buffer) {
    assertThat(buffer.offer(1), is(true));
    assertThat(buffer.size(), is(POPULATED_SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void offer_whenFull(MpscGrowableArrayQueue<Integer> buffer) {
    assertThat(buffer.offer(1), is(false));
    assertThat(buffer.size(), is(FULL_SIZE));
  }

  /* --------------- Poll --------------- */

  @Test(dataProvider = "empty")
  public void poll_whenEmpty(MpscGrowableArrayQueue<Integer> buffer) {
    assertThat(buffer.poll(), is(nullValue()));
  }

  @Test(dataProvider = "populated")
  public void poll_whenPopulated(MpscGrowableArrayQueue<Integer> buffer) {
    assertThat(buffer.poll(), is(not(nullValue())));
    assertThat(buffer.size(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "full")
  public void poll_toEmpty(MpscGrowableArrayQueue<Integer> buffer) {
    while (buffer.poll() != null) {}
    assertThat(buffer.size(), is(0));
  }

  /* --------------- Concurrency --------------- */

  @Test(dataProvider = "empty")
  public void oneProducer_oneConsumer(MpscGrowableArrayQueue<Integer> buffer) {
    AtomicInteger started = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();

    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(2));
      for (int i = 0; i < PRODUCE; i++) {
        while (!buffer.offer(i)) {}
      }
      finished.incrementAndGet();
    });
    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(2));
      for (int i = 0; i < PRODUCE; i++) {
        while (buffer.poll() == null) {}
      }
      finished.incrementAndGet();
    });

    await().untilAtomic(finished, is(2));
    assertThat(buffer.size(), is(0));
  }

  @Test(dataProvider = "empty")
  public void manyProducers_noConsumer(MpscGrowableArrayQueue<Integer> buffer) {
    AtomicInteger count = new AtomicInteger();
    ConcurrentTestHarness.timeTasks(NUM_PRODUCERS, () -> {
      for (int i = 0; i < PRODUCE; i++) {
        if (buffer.offer(i)) {
          count.incrementAndGet();
        }
      }
    });
    assertThat(buffer.size(), is(count.get()));
  }

  @Test(dataProvider = "empty")
  public void manyProducers_oneConsumer(MpscGrowableArrayQueue<Integer> buffer) {
    AtomicInteger started = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();

    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(NUM_PRODUCERS + 1));
      for (int i = 0; i < (NUM_PRODUCERS * PRODUCE); i++) {
        while (buffer.poll() == null) {}
      }
      finished.incrementAndGet();
    });

    ConcurrentTestHarness.timeTasks(NUM_PRODUCERS, () -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(NUM_PRODUCERS + 1));
      for (int i = 0; i < PRODUCE; i++) {
        while (!buffer.offer(i)) {}
      }
      finished.incrementAndGet();
    });

    await().untilAtomic(finished, is(NUM_PRODUCERS + 1));
    assertThat(buffer.size(), is(0));
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
    MpscGrowableArrayQueue<Integer> buffer = new MpscGrowableArrayQueue<>(4, FULL_SIZE);
    for (int i = 0; i < items; i++) {
      buffer.offer(i);
    }
    return buffer;
  }
}
