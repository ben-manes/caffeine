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

import static com.github.benmanes.caffeine.IsValidSingleConsumerQueue.validate;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IsEmptyIterable.deeplyEmpty;
import static com.google.common.collect.Iterators.elementsEqual;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.SingleConsumerQueueTest.ValidatingQueueListener;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.testing.SerializableTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("deprecation")
@Listeners(ValidatingQueueListener.class)
public class SingleConsumerQueueTest {
  private static final int PRODUCE = 10_000;
  private static final int NUM_PRODUCERS = 10;
  private static final int POPULATED_SIZE = 10;

  @Test(dataProvider = "empty")
  public void clear_whenEmpty(Queue<?> queue) {
    queue.clear();
    assertThat(queue, is(deeplyEmpty()));
  }

  @Test(dataProvider = "populated")
  public void clear_whenPopulated(Queue<?> queue) {
    queue.clear();
    assertThat(queue, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void isEmpty_whenEmpty(Queue<?> queue) {
    assertThat(queue.isEmpty(), is(true));
  }

  @Test(dataProvider = "populated")
  public void isEmpty_whenPopulated(Queue<?> queue) {
    assertThat(queue.isEmpty(), is(false));
  }

  @Test(dataProvider = "empty")
  public void size_whenEmpty(Queue<?> queue) {
    assertThat(queue.size(), is(0));
    assertThat(queue.size(), is(equalTo(Iterables.size(queue))));
  }

  @Test(dataProvider = "populated")
  public void size_whenPopulated(Queue<?> queue) {
    assertThat(queue.size(), is(POPULATED_SIZE));
    assertThat(Iterables.size(queue), is(POPULATED_SIZE));
    assertThat(queue.size(), is(equalTo(Iterables.size(queue))));
  }

  /* --------------- Contains --------------- */

  @Test(dataProvider = "empty")
  public void contains_withNull(Queue<?> queue) {
    assertThat(queue.contains(null), is(false));
  }

  @Test(dataProvider = "populated")
  public void contains_whenFound(Queue<Integer> queue) {
    assertThat(queue.contains(Iterables.get(queue, POPULATED_SIZE / 2)), is(true));
  }

  @Test(dataProvider = "populated")
  public void contains_whenNotFound(Queue<Integer> queue) {
    assertThat(queue.contains(-1), is(false));
  }

  @SuppressWarnings("ReturnValueIgnored")
  @Test(dataProvider = "empty", expectedExceptions = NullPointerException.class)
  public void containsAll_withNull(Queue<?> queue) {
    queue.containsAll(null);
  }

  @Test(dataProvider = "populated")
  @SuppressWarnings("ModifyingCollectionWithItself")
  public void containsAll_whenFound(Queue<Integer> queue) {
    assertThat(queue.containsAll(
        ImmutableList.of(0, POPULATED_SIZE / 2, POPULATED_SIZE - 1)), is(true));
    assertThat(queue.containsAll(queue), is(true));
  }

  @Test(dataProvider = "populated")
  public void containsAll_whenNotFound(Queue<Integer> queue) {
    assertThat(queue.containsAll(
        ImmutableList.of(-1, -(POPULATED_SIZE / 2), -POPULATED_SIZE)), is(false));
  }

  /* --------------- Peek --------------- */

  @Test(dataProvider = "empty")
  public void peek_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.peek(), is(nullValue()));
  }

  @Test(dataProvider = "populated")
  public void peek_whenPopulated(SingleConsumerQueue<Integer> queue) {
    Integer first = queue.head.next.value;
    assertThat(queue.peek(), is(first));
    assertThat(queue, hasSize(POPULATED_SIZE));
    assertThat(queue.contains(first), is(true));
  }

  /* --------------- Element --------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void element_whenEmpty(Queue<Integer> queue) {
    queue.element();
  }

  @Test(dataProvider = "populated")
  public void element_whenPopulated(SingleConsumerQueue<Integer> queue) {
    Integer first = queue.head.next.value;
    assertThat(queue.element(), is(first));
    assertThat(queue, hasSize(POPULATED_SIZE));
    assertThat(queue.contains(first), is(true));
  }

  /* --------------- Offer --------------- */

  @Test(dataProvider = "empty")
  public void offer_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.offer(1), is(true));
    assertThat(queue, hasSize(1));
  }

  @Test(dataProvider = "populated")
  public void offer_whenPopulated(Queue<Integer> queue) {
    assertThat(queue.offer(1), is(true));
    assertThat(queue, hasSize(POPULATED_SIZE + 1));
  }

  /* --------------- Add --------------- */

  @Test(dataProvider = "empty")
  public void add_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.add(1), is(true));
    assertThat(queue.peek(), is(1));
    assertThat(Iterables.getLast(queue), is(1));
    assertThat(queue, hasSize(1));
    assertThat(queue.size(), is(equalTo(Iterables.size(queue))));
  }

  @Test(dataProvider = "populated")
  public void add_whenPopulated(Queue<Integer> queue) {
    assertThat(queue.add(-1), is(true));
    assertThat(queue.peek(), is(not(-1)));
    assertThat(Iterables.getLast(queue), is(-1));
    assertThat(queue, hasSize(POPULATED_SIZE + 1));
    assertThat(queue.size(), is(equalTo(Iterables.size(queue))));
  }

  @Test(dataProvider = "empty")
  public void addAll_whenEmpty(Queue<Integer> queue) {
    List<Integer> list = new ArrayList<>();
    populate(list, POPULATED_SIZE);

    assertThat(queue.addAll(list), is(true));
    assertThat(queue.peek(), is(0));
    assertThat(Iterables.getLast(queue), is(POPULATED_SIZE - 1));
    assertThat(String.format("%nExpected: %s%n     but: %s", queue, list),
      elementsEqual(queue.iterator(), list.iterator()));
  }

  @Test(dataProvider = "singleton,populated")
  public void addAll_whenPopulated(Queue<Integer> queue) {
    List<Integer> list = ImmutableList.of(POPULATED_SIZE, POPULATED_SIZE + 1, POPULATED_SIZE + 2);
    List<Integer> expect = ImmutableList.copyOf(Iterables.concat(queue, list));

    assertThat(queue.addAll(list), is(true));
    assertThat(queue.peek(), is(0));
    assertThat(Iterables.getLast(queue), is(POPULATED_SIZE + 2));
    assertThat(String.format("%nExpected: %s%n     but: %s", queue, expect),
        elementsEqual(queue.iterator(), expect.iterator()));
  }

  /* --------------- Poll --------------- */

  @Test(dataProvider = "empty")
  public void poll_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.poll(), is(nullValue()));
  }

  @Test(dataProvider = "populated")
  public void poll_whenPopulated(Queue<Integer> queue) {
    Integer first = queue.peek();
    assertThat(queue.poll(), is(first));
    assertThat(queue, hasSize(POPULATED_SIZE - 1));
    assertThat(queue.contains(first), is(false));
  }

  @Test(dataProvider = "populated")
  public void poll_toEmpty(Queue<Integer> queue) {
    Integer value;
    while ((value = queue.poll()) != null) {
      assertThat(queue.contains(value), is(false));
    }
    assertThat(queue, is(deeplyEmpty()));
  }

  /* --------------- Remove --------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void remove_whenEmpty(Queue<Integer> queue) {
    queue.remove();
  }

  @Test(dataProvider = "populated")
  public void remove_whenPopulated(Queue<Integer> queue) {
    Integer first = queue.peek();
    assertThat(queue.remove(), is(first));
    assertThat(queue, hasSize(POPULATED_SIZE - 1));
    assertThat(queue.contains(first), is(false));
  }

  @Test(dataProvider = "populated")
  public void remove_toEmpty(Queue<Integer> queue) {
    while (!queue.isEmpty()) {
      Integer value = queue.remove();
      assertThat(queue.contains(value), is(false));
    }
    assertThat(queue, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty,singleton,populated")
  public void removeElement_notFound(Queue<Integer> queue) {
    assertThat(queue.remove(-1), is(false));
  }

  @Test(dataProvider = "populated")
  public void removeElement_whenFound(Queue<Integer> queue) {
    Integer first = queue.peek();
    assertThat(queue.remove(first), is(true));
    assertThat(queue, hasSize(POPULATED_SIZE - 1));
    assertThat(queue.contains(first), is(false));
  }

  @Test(dataProvider = "populated")
  public void removeElement_toEmpty(Queue<Integer> queue) {
    while (!queue.isEmpty()) {
      Integer value = queue.peek();
      assertThat(queue.remove(value), is(true));
      assertThat(queue.contains(value), is(false));
    }
    assertThat(queue, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeAll_withEmpty(Queue<Integer> queue) {
    assertThat(queue.removeAll(ImmutableList.of()), is(false));
    assertThat(queue, is(deeplyEmpty()));
  }

  @Test(dataProvider = "populated")
  public void removeAll_withPopulated(Queue<Integer> queue) {
    Integer first = queue.peek();
    assertThat(queue.removeAll(ImmutableList.of(first)), is(true));
    assertThat(queue, hasSize(POPULATED_SIZE - 1));
    assertThat(queue.contains(first), is(false));
  }

  @Test(dataProvider = "populated")
  public void removeAll_toEmpty(Queue<Integer> queue) {
    assertThat(queue.removeAll(ImmutableList.copyOf(queue)), is(true));
    assertThat(queue, is(deeplyEmpty()));
  }

  /* --------------- Retain --------------- */

  @Test(dataProvider = "empty")
  public void retainAll_withEmpty(Queue<Integer> queue) {
    assertThat(queue.retainAll(ImmutableList.of()), is(false));
    assertThat(queue, is(deeplyEmpty()));
  }

  @Test(dataProvider = "populated")
  public void retainAll_withPopulated(Queue<Integer> queue) {
    Integer first = queue.peek();
    assertThat(queue.retainAll(ImmutableList.of(first)), is(true));
    assertThat(queue, hasSize(1));
    assertThat(queue.contains(first), is(true));
  }

  @Test(dataProvider = "populated")
  public void retainAll_toEmpty(Queue<Integer> queue) {
    assertThat(queue.retainAll(ImmutableList.of()), is(true));
    assertThat(queue, is(deeplyEmpty()));
  }

  /* --------------- Iterators --------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void iterator_noMoreElements(Queue<Integer> queue) {
    queue.iterator().next();
  }

  @Test(dataProvider = "empty")
  public void iterator_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.iterator().hasNext(), is(false));
  }

  @Test(dataProvider = "singleton,populated")
  public void iterator_whenPopulated(Queue<Integer> queue) {
    List<Integer> copy = new ArrayList<>();
    populate(copy, queue.size());
    assertThat(String.format("%nExpected: %s%n     but: %s", queue, copy),
        elementsEqual(queue.iterator(), copy.iterator()));
  }

  @Test(dataProvider = "populated", expectedExceptions = IllegalStateException.class)
  public void iterator_removal_unread(Queue<Integer> queue) {
    queue.iterator().remove();
  }

  @Test(dataProvider = "populated", expectedExceptions = IllegalStateException.class)
  public void iterator_removal_duplicate(Queue<Integer> queue) {
    Iterator<Integer> it = queue.iterator();
    it.next();
    it.remove();
    it.remove();
  }

  @Test(dataProvider = "populated")
  public void iterator_removal(Queue<Integer> queue) {
    Iterator<Integer> it = queue.iterator();
    it.next();
    it.remove();
  }

  @Test(dataProvider = "populated")
  public void iterator_removal_toEmpty(Queue<Integer> queue) {
    for (Iterator<Integer> it = queue.iterator(); it.hasNext();) {
      it.next();
      it.remove();
    }
    assertThat(queue, is(deeplyEmpty()));
  }

  /* --------------- toArray --------------- */

  @Test(dataProvider = "empty,singleton,populated")
  public void toArray(Queue<Integer> queue) {
    Object[] expect = new ArrayList<>(queue).toArray();
    Object[] actual = queue.toArray();
    assertThat(actual, queue.isEmpty() ? emptyArray() : arrayContaining(expect));
  }

  @Test(dataProvider = "empty,singleton,populated")
  public void toTypedArray(Queue<Integer> queue) {
    Integer[] expect = new ArrayList<>(queue).toArray(new Integer[] {});
    Integer[] actual = queue.toArray(new Integer[] {});
    assertThat(actual, queue.isEmpty() ? emptyArray() : arrayContaining(expect));
  }

  /* --------------- toString --------------- */

  @Test(dataProvider = "empty,singleton,populated")
  public void toString(Queue<Integer> queue) {
    List<Integer> list = new ArrayList<>();
    populate(list, queue.size());
    assertThat(queue, hasToString(list.toString()));
  }

  /* --------------- Serialization --------------- */

  @Test(dataProvider = "empty,singleton,populated")
  public void serializable(Queue<Integer> queue) {
    Queue<Integer> copy = SerializableTester.reserialize(queue);
    assertThat(String.format("%nExpected: %s%n     but: %s", queue, copy),
        elementsEqual(queue.iterator(), copy.iterator()));
  }

  /* --------------- Concurrency --------------- */

  @Test(dataProvider = "empty")
  public void oneProducer_oneConsumer(Queue<Integer> queue) {
    AtomicInteger started = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();

    ConcurrentTestHarness.execute(() -> {
      started.incrementAndGet();
      await().untilAtomic(started, is(2));
      for (int i = 0; i < PRODUCE; i++) {
        queue.add(i);
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
    assertThat(queue, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void manyProducers_noConsumer(Queue<Integer> queue) {
    ConcurrentTestHarness.timeTasks(NUM_PRODUCERS, () -> {
      for (int i = 0; i < PRODUCE; i++) {
        queue.add(i);
      }
    });
    assertThat(queue, hasSize(NUM_PRODUCERS * PRODUCE));
    assertThat(queue.size(), is(equalTo(Iterables.size(queue))));
  }

  @Test(dataProvider = "empty")
  public void manyProducers_oneConsumer(Queue<Integer> queue) {
    AtomicInteger started = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();

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
        queue.add(i);
      }
      finished.incrementAndGet();
    });

    await().untilAtomic(finished, is(NUM_PRODUCERS + 1));
    assertThat(queue, is(deeplyEmpty()));
  }

  /* --------------- Queue providers --------------- */

  @DataProvider(name = "empty")
  public Object[][] providesEmpty() {
    return new Object[][] {{ makePopulated(0, true) }, { makePopulated(0, false) }};
  }

  @DataProvider(name = "singleton")
  public Object[][] providesSingleton() {
    return new Object[][] {{ makePopulated(1, true) }, { makePopulated(1, false) }};
  }

  @DataProvider(name = "populated")
  public Object[][] providesPopulated() {
    return new Object[][] {
        { makePopulated(POPULATED_SIZE, true) },
        { makePopulated(POPULATED_SIZE, false) }};
  }

  @DataProvider(name = "singleton,populated")
  public Object[][] providesSingletonAndPopulated() {
    return new Object[][] {
        { makePopulated(1, true) }, { makePopulated(1, false) },
        { makePopulated(POPULATED_SIZE, true) }, { makePopulated(POPULATED_SIZE, false) }};
  }

  @DataProvider(name = "empty,singleton,populated")
  public Object[][] providesEmptyAndSingletonAndPopulated() {
    return new Object[][] {
        { makePopulated(0, true) }, { makePopulated(0, false) },
        { makePopulated(1, true) }, { makePopulated(1, false) },
        { makePopulated(POPULATED_SIZE, true) }, { makePopulated(POPULATED_SIZE, false) }};
  }

  static SingleConsumerQueue<Integer> makePopulated(int size, boolean optimistic) {
    SingleConsumerQueue<Integer> queue = optimistic
        ? SingleConsumerQueue.optimistic()
        : SingleConsumerQueue.linearizable();
    populate(queue, size);
    return queue;
  }

  static void populate(Collection<Integer> collection, int start) {
    for (int i = 0; i < start; i++) {
      collection.add(i);
    }
  }

  /** A listener that validates the internal structure after a successful test execution. */
  public static final class ValidatingQueueListener implements IInvokedMethodListener {
    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {}

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
      try {
        if (testResult.isSuccess()) {
          for (Object param : testResult.getParameters()) {
            if (param instanceof SingleConsumerQueue<?>) {
              assertThat((SingleConsumerQueue<?>) param, is(validate()));
            }
          }
        }
      } catch (AssertionError caught) {
        testResult.setStatus(ITestResult.FAILURE);
        testResult.setThrowable(caught);
      } finally {
        cleanUp(testResult);
      }
    }
  }

  /** Free memory by clearing unused resources after test execution. */
  static void cleanUp(ITestResult testResult) {
    Object[] params = testResult.getParameters();
    for (int i = 0; i < params.length; i++) {
      Object param = params[i];
      if ((param instanceof SingleConsumerQueue<?>)) {
        SingleConsumerQueue.Node<?> node = ((SingleConsumerQueue<?>) param).factory.apply(null);
        boolean linearizable = (node instanceof SingleConsumerQueue.LinearizableNode<?>);
        params[i] = param.getClass().getSimpleName() + "_"
            + (linearizable ? "linearizable" : "optimistic");
      } else {
        params[i] = Objects.toString(param);
      }
    }
  }
}
