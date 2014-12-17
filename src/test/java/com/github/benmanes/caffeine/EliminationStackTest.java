/*
 * Copyright 2013 Ben Manes. All Rights Reserved.
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

import static com.google.common.collect.Iterators.elementsEqual;
import static com.jayway.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.testing.SerializableTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EliminationStackTest {
  static final int POPULATED_SIZE = 100;

  @Test(dataProvider = "empty")
  public void clear_whenEmpty(EliminationStack<Integer> stack) {
    stack.clear();
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void clear_whenPopulated(EliminationStack<Integer> stack) {
    stack.clear();
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void isEmpty_whenEmpty(EliminationStack<Integer> stack) {
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "populated")
  public void isEmpty_whenPopulated(EliminationStack<Integer> stack) {
    assertThat(stack.isEmpty(), is(false));
  }

  @Test(dataProvider = "empty")
  public void size_whenEmpty(EliminationStack<Integer> stack) {
    assertThat(stack.size(), is(0));
  }

  @Test(dataProvider = "populated")
  public void size_whenPopulated(EliminationStack<Integer> stack) {
    assertThat(stack.size(), is(POPULATED_SIZE));
  }

  @Test(dataProvider = "empty", expectedExceptions = NullPointerException.class)
  public void contains_withNull(EliminationStack<Integer> stack) {
    stack.contains(null);
  }

  @Test(dataProvider = "populated")
  public void contains_whenFound(EliminationStack<Integer> stack) {
    assertThat(stack.contains(1), is(true));
  }

  @Test(dataProvider = "populated")
  public void contains_whenNotFound(EliminationStack<Integer> stack) {
    assertThat(stack.contains(-1), is(false));
  }

  @Test(dataProvider = "empty", expectedExceptions = NullPointerException.class)
  public void push_withNull(EliminationStack<Integer> stack) {
    stack.push(null);
  }

  @Test(dataProvider = "empty")
  public void push_whenEmpty(EliminationStack<Integer> stack) {
    stack.push(1);
    assertThat(stack.peek(), is(1));
    assertThat(stack.size(), is(1));
  }

  @Test(dataProvider = "populated")
  public void push_whenPopulated(EliminationStack<Integer> stack) {
    stack.push(1);
    assertThat(stack.peek(), is(1));
    assertThat(stack.size(), is(POPULATED_SIZE + 1));
  }

  @Test(dataProvider = "empty")
  public void peek_whenEmpty(EliminationStack<Integer> stack) {
    assertThat(stack.peek(), is(nullValue()));
  }

  @Test(dataProvider = "populated")
  public void peek_whenPopulated(EliminationStack<Integer> stack) {
    assertThat(stack.peek(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "populated")
  public void peek_deadNode(EliminationStack<Integer> stack) {
    Iterator<Integer> it = stack.iterator();
    it.next();
    it.remove();
    assertThat(stack.peek(), is(POPULATED_SIZE - 2));
  }

  @Test(dataProvider = "empty")
  public void pop_whenEmpty(EliminationStack<Integer> stack) {
    assertThat(stack.pop(), is(nullValue()));
  }

  @Test(dataProvider = "populated")
  public void pop_whenPopulated(EliminationStack<Integer> stack) {
    Integer first = stack.peek();
    assertThat(stack.pop(), is(first));
    assertThat(stack, not(contains(first)));
    assertThat(stack.size(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "populated")
  public void pop_toEmpty(EliminationStack<Integer> stack) {
    while (!stack.isEmpty()) {
      Integer value = stack.pop();
      assertThat(stack.contains(value), is(false));
    }
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void remove_whenEmpty(EliminationStack<Integer> stack) {
    assertThat(stack.remove(123), is(false));
  }

  @Test(dataProvider = "populated")
  public void remove_whenPopulated(EliminationStack<Integer> stack) {
    assertThat(stack.remove(10), is(true));
    assertThat(stack, not(contains(10)));
    assertThat(stack.size(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "populated")
  public void remove_toEmpty(EliminationStack<Integer> stack) {
    while (!stack.isEmpty()) {
      Integer value = stack.peek();
      assertThat(stack.remove(value), is(true));
      assertThat(stack.contains(value), is(false));
    }
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void concurrent(final EliminationStack<Integer> stack) throws Exception {
    final LongAdder pushed = new LongAdder();
    final LongAdder popped = new LongAdder();

    ConcurrentTestHarness.timeTasks(10, () -> {
      stack.push(ThreadLocalRandom.current().nextInt());
      pushed.increment();

      if (stack.pop() != null) {
        popped.increment();
      }
    });

    for (AtomicReference<Object> slot : stack.arena) {
      assertThat(slot.get(), is(nullValue()));
    }
    assertThat(pushed.intValue(), is(equalTo(stack.size() + popped.intValue())));
  }

  @Test(dataProvider = "empty")
  public void scanAndTransfer(final EliminationStack<String> stack) {
    final AtomicBoolean started = new AtomicBoolean();
    final AtomicBoolean done = new AtomicBoolean();
    final String value = "test";
    final int startIndex = 1;
    new Thread(() -> {
      started.set(true);
      while (!done.get()) {
        if (stack.scanAndTransferToWaiter(value, startIndex)) {
          done.set(true);
        }
      }
    }).start();
    await().untilTrue(started);

    try {
      final AtomicReference<String> found = new AtomicReference<>();
      await().until(() -> {
        found.set(stack.awaitMatch(startIndex));
        return (found.get() != null);
      });
      assertThat(found.get(), is(equalTo(value)));
    } finally {
      done.set(true);
    }
  }

  @Test(dataProvider = "empty")
  public void awaitExchange(final EliminationStack<String> stack) {
    final AtomicBoolean started = new AtomicBoolean();
    final AtomicBoolean done = new AtomicBoolean();
    final String value = "test";
    final int startIndex = 1;
    new Thread(() -> {
      started.set(true);
      while (!done.get()) {
        if (stack.awaitExchange(value, startIndex)) {
          done.set(true);
        }
      }
    }).start();
    await().untilTrue(started);

    try {
      final AtomicReference<String> found = new AtomicReference<>();
      await().until(() -> {
        found.set(stack.awaitMatch(startIndex));
        return (found.get() != null);
      });
      assertThat(found.get(), is(equalTo(value)));
    } finally {
      done.set(true);
    }
  }

  @Test(dataProvider = "empty")
  public void scanAndMatch(final EliminationStack<String> stack) {
    final AtomicBoolean started = new AtomicBoolean();
    final AtomicBoolean done = new AtomicBoolean();
    final String value = "test";
    final int startIndex = 1;
    new Thread(() -> {
      started.set(true);
      while (!done.get()) {
        if (stack.awaitExchange(value, startIndex)) {
          done.set(true);
        }
      }
    }).start();
    await().untilTrue(started);

    try {
      final AtomicReference<String> found = new AtomicReference<>();
      await().until(() -> {
        found.set(stack.scanAndMatch(startIndex));
        return (found.get() != null);
      });
      assertThat(found.get(), is(equalTo(value)));
    } finally {
      done.set(true);
    }
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void iterator_noMoreElements(EliminationStack<Integer> stack) {
    stack.iterator().next();
  }

  @Test(dataProvider = "populated", expectedExceptions = IllegalStateException.class)
  public void iterator_removal_unread(EliminationStack<Integer> stack) {
    stack.iterator().remove();
  }

  @Test(dataProvider = "populated", expectedExceptions = IllegalStateException.class)
  public void iterator_removal_duplicate(EliminationStack<Integer> stack) {
    Iterator<Integer> it = stack.iterator();
    it.next();
    it.remove();
    it.remove();
  }

  @Test(dataProvider = "empty")
  public void iterator_whenEmpty(EliminationStack<Integer> stack) {
    assertThat(stack.iterator().hasNext(), is(false));
  }

  @Test(dataProvider = "populated")
  public void iterator_whenPopulated(EliminationStack<Integer> stack) {
    List<Integer> list = new ArrayList<>();
    populate(list, POPULATED_SIZE);
    Collections.reverse(list);
    assertThat(String.format("\nExpected: %s%n     but: %s", stack, list),
        elementsEqual(stack.iterator(), list.iterator()));
  }

  @Test(dataProvider = "empty", expectedExceptions = IllegalStateException.class)
  public void iterator_removalWhenEmpty(EliminationStack<Integer> stack) {
    stack.iterator().remove();
  }

  @Test(dataProvider = "populated")
  public void iterator_removalWhenPopulated(EliminationStack<Integer> stack) {
    Iterator<Integer> it = stack.iterator();
    Integer first = stack.peek();
    it.next();
    it.remove();
    assertThat(stack, not(contains(first)));
    assertThat(stack.size(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "empty")
  public void serialize_whenEmpty(EliminationStack<Integer> stack) {
    List<Integer> expected = new ArrayList<>(stack);
    List<Integer> actual = new ArrayList<>(SerializableTester.reserialize(stack));
    assertThat(expected, is(equalTo(actual)));
  }

  @Test(dataProvider = "populated")
  public void serialize_whenPopulated(EliminationStack<Integer> stack) {
    List<Integer> expected = new ArrayList<>(stack);
    List<Integer> actual = new ArrayList<>(SerializableTester.reserialize(stack));
    assertThat(expected, is(equalTo(actual)));
  }

  /* ---------------- Stack providers -------------- */

  @DataProvider
  public Object[][] emptyStack() {
    return new Object[][] {{ new EliminationStack<Integer>() }};
  }

  @DataProvider
  public Object[][] populatedStack() {
    return new Object[][] {{ newPopulatedStack() }};
  }

  EliminationStack<Integer> newPopulatedStack() {
    EliminationStack<Integer> stack = new EliminationStack<Integer>();
    populate(stack, POPULATED_SIZE);
    return stack;
  }

  static void populate(Collection<Integer> collection, int start) {
    for (int i = 0; i < start; i++) {
      collection.add(i);
    }
  }
}
