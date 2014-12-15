/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.Queue;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class SingleConsumerQueueTest {
  private static final int POPULATED_SIZE = 100;

  /* ---------------- Offer -------------- */

  @Test(dataProvider = "empty")
  public void offer_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.offer(1), is(true));
    //assertThat(queue, hasSize(1));
  }

  @Test(dataProvider = "populated")
  public void offer_whenPopulated(Queue<Integer> queue) {
    assertThat(queue.offer(1), is(true));
    //assertThat(queue, hasSize(POPULATED_SIZE));
  }

  /* ---------------- Poll -------------- */

  @Test(dataProvider = "empty")
  public void poll_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.poll(), is(nullValue()));
  }

  @Test(enabled = false, dataProvider = "populated")
  public void poll_whenPopulated(Queue<Integer> queue) {
    Integer first = queue.peek();
    assertThat(queue.poll(), is(first));
    //assertThat(queue, hasSize(POPULATED_SIZE));
    assertThat(queue.contains(first), is(false));
  }

  @Test(dataProvider = "populated")
  public void poll_toEmpty(Queue<Integer> queue) {
    Integer value;
    while ((value = queue.poll()) != null) {
      assertThat(queue.contains(value), is(false));
    }
    // assertThat(queue, is(emptyCollection()));
  }

  /* ---------------- Queue providers -------------- */

  @DataProvider
  public Object[][] empty() {
    return new Object[][] {{ new SingleConsumerQueue<Integer>() }};
  }

  @DataProvider(name = "populated")
  public Object[][] providesWarmedDeque() {
    Queue<Integer> queue = new SingleConsumerQueue<>();
    populate(queue);
    return new Object[][] {{ queue }};
  }

  static void populate(Queue<Integer> queue) {
    for (int i = 0; i < POPULATED_SIZE; i++) {
      queue.add(i);
    }
  }
}
