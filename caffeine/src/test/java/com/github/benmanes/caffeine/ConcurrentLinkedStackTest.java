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

import static com.github.benmanes.caffeine.IsValidConcurrentLinkedStack.validate;
import static com.google.common.collect.Iterators.elementsEqual;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.ConcurrentLinkedStack.Node;
import com.github.benmanes.caffeine.ConcurrentLinkedStackTest.ValidatingStackListener;
import com.github.benmanes.caffeine.base.UnsafeAccess;
import com.google.common.collect.Iterables;
import com.google.common.testing.SerializableTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(ValidatingStackListener.class)
public final class ConcurrentLinkedStackTest {
  private static final int POPULATED_SIZE = 5;
  private static final int NUM_THREADS = 10;
  private static final int COUNT = 10_000;

  @Test(dataProvider = "empty")
  public void clear_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    stack.clear();
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void clear_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    stack.clear();
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void isEmpty_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "populated")
  public void isEmpty_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.isEmpty(), is(false));
  }

  @Test(dataProvider = "empty")
  public void size_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.size(), is(0));
  }

  @Test(dataProvider = "populated")
  public void size_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.size(), is(POPULATED_SIZE));
  }

  @Test(dataProvider = "empty")
  public void contains_withNull(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.contains(null), is(false));
  }

  @Test(dataProvider = "populated")
  public void contains_whenFound(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.contains(1), is(true));
  }

  @Test(dataProvider = "populated")
  public void contains_whenNotFound(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.contains(-1), is(false));
  }

  @Test(dataProvider = "empty", expectedExceptions = NullPointerException.class)
  public void push_withNull(ConcurrentLinkedStack<Integer> stack) {
    stack.push(null);
  }

  @Test(dataProvider = "empty")
  public void push_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    stack.push(1);
    assertThat(stack.peek(), is(1));
    assertThat(stack.size(), is(1));
  }

  @Test(dataProvider = "populated")
  public void push_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    stack.push(1);
    assertThat(stack.peek(), is(1));
    assertThat(stack.size(), is(POPULATED_SIZE + 1));
  }

  @Test(dataProvider = "empty")
  public void peek_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.peek(), is(nullValue()));
  }

  @Test(dataProvider = "populated")
  public void peek_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.peek(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "populated")
  public void peek_deadNode(ConcurrentLinkedStack<Integer> stack) {
    Iterator<Integer> it = stack.iterator();
    it.next();
    it.remove();
    assertThat(stack.peek(), is(POPULATED_SIZE - 2));
  }

  @Test(dataProvider = "empty")
  public void pop_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.pop(), is(nullValue()));
  }

  @Test(dataProvider = "populated")
  public void pop_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    Integer first = stack.peek();
    assertThat(stack.pop(), is(first));
    assertThat(stack, not(contains(first)));
    assertThat(stack.size(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "populated")
  public void pop_toEmpty(ConcurrentLinkedStack<Integer> stack) {
    while (!stack.isEmpty()) {
      Integer value = stack.pop();
      assertThat(stack.contains(value), is(false));
    }
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void remove_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.remove(123), is(false));
  }

  @Test(dataProvider = "populated")
  public void remove_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.remove(POPULATED_SIZE / 2), is(true));
    assertThat(stack, not(contains(POPULATED_SIZE / 2)));
    assertThat(stack.size(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "populated")
  public void remove_toEmpty(ConcurrentLinkedStack<Integer> stack) {
    while (!stack.isEmpty()) {
      Integer value = stack.peek();
      assertThat(stack.remove(value), is(true));
      assertThat(stack.contains(value), is(false));
    }
    assertThat(stack.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void asLifoQueue(ConcurrentLinkedStack<Integer> stack) {
    Queue<Integer> queue = stack.asLifoQueue();
    assertThat(queue.offer(1), is(true));
    assertThat(queue.offer(2), is(true));
    assertThat(queue.offer(3), is(true));
    assertThat(queue.isEmpty(), is(false));
    assertThat(queue.contains(1), is(true));
    assertThat(queue.size(), is(3));
    assertThat(queue.peek(), is(3));
    assertThat(queue.poll(), is(3));
    assertThat(queue.remove(2), is(true));
    assertThat(queue.iterator().hasNext(), is(true));
    queue.clear();
    assertThat(queue.isEmpty(), is(true));
  }

  @Test(dataProvider = "empty")
  public void transfer_pushToPop(ConcurrentLinkedStack<Integer> stack) {
    ConcurrentTestHarness.execute(() -> {
      setThreadIndex();
      Node<Integer> node = new Node<>(1);
      while (stack.transferOrCombine(node, node) != null) {}
    });
    Awaits.await().until(() -> {
      setThreadIndex();
      return stack.tryReceive();
    }, is(not(nullValue())));
  }

  @Test(dataProvider = "empty")
  public void transfer_pushToPop_batch(ConcurrentLinkedStack<Integer> stack) {
    ConcurrentTestHarness.execute(() -> {
      setThreadIndex();
      Node<Integer> first = new Node<>(1);
      Node<Integer> last = first;
      for (int i = 0; i < POPULATED_SIZE; i++) {
        last.next = new Node<>(i);
        last = last.next;
      }
      while (stack.transferOrCombine(first, last) != null) {}
    });
    Awaits.await().until(() -> {
      setThreadIndex();
      return stack.tryReceive();
    }, is(not(nullValue())));
    assertThat(stack, hasSize(POPULATED_SIZE));
  }

  @Test(dataProvider = "empty")
  public void transfer_pushToPush(ConcurrentLinkedStack<Integer> stack) {
    AtomicBoolean transferred = new AtomicBoolean();
    AtomicBoolean received = new AtomicBoolean();

    ConcurrentTestHarness.execute(() -> {
      setThreadIndex();
      Node<Integer> node = new Node<>(1);
      for (;;) {
        Node<Integer> result = stack.transferOrCombine(node, node);
        if (result == null) {
          transferred.set(true);
          break;
        } else if (result != node) {
          received.set(true);
          break;
        }
      }
    });
    Node<Integer> node = new Node<>(2);
    Awaits.await().until(() -> {
      setThreadIndex();
      Node<Integer> result = stack.transferOrCombine(node, node);
      if (result == null) {
        Awaits.await().untilTrue(received);
        return true;
      } else if (result != node) {
        Awaits.await().untilTrue(transferred);
        return true;
      }
      return false;
    });
  }

  private static void setThreadIndex() {
    UnsafeAccess.UNSAFE.putInt(Thread.currentThread(), ConcurrentLinkedStack.PROBE, 1);
  }

  @Test(dataProvider = "empty")
  public void concurrent_push(ConcurrentLinkedStack<Integer> stack) {
    ConcurrentTestHarness.timeTasks(NUM_THREADS, () -> {
      for (int i = 0; i < COUNT; i++) {
        stack.push(i);
      }
    });
    assertThat(stack, hasSize(NUM_THREADS * COUNT));
    assertThat(stack.size(), is(equalTo(Iterables.size(stack))));
  }

  @Test(dataProvider = "empty")
  public void concurrent_pop(ConcurrentLinkedStack<Integer> stack) {
    IntStream.range(0, NUM_THREADS * COUNT).forEach(stack::push);

    ConcurrentTestHarness.timeTasks(NUM_THREADS, () -> {
      int count = 0;
      do {
        if (stack.pop() != null) {
          count++;
        }
      } while (count != COUNT);
    });
    assertThat(stack, is(empty()));
  }

  @Test(dataProvider = "empty")
  public void concurrent_pushAndPop(ConcurrentLinkedStack<Integer> stack) {
    LongAdder pushed = new LongAdder();
    LongAdder popped = new LongAdder();

    ConcurrentTestHarness.timeTasks(10, () -> {
      for (int i = 0; i < 100; i++) {
        stack.push(ThreadLocalRandom.current().nextInt());
        pushed.increment();

        Thread.yield();
        if (stack.pop() != null) {
          popped.increment();
        }
      }
    });

    for (AtomicReference<?> slot : stack.arena) {
      assertThat(slot.get(), is(nullValue()));
    }
    assertThat(pushed.intValue(), is(equalTo(stack.size() + popped.intValue())));
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void iterator_noMoreElements(ConcurrentLinkedStack<Integer> stack) {
    stack.iterator().next();
  }

  @Test(dataProvider = "populated", expectedExceptions = IllegalStateException.class)
  public void iterator_removal_unread(ConcurrentLinkedStack<Integer> stack) {
    stack.iterator().remove();
  }

  @Test(dataProvider = "populated", expectedExceptions = IllegalStateException.class)
  public void iterator_removal_duplicate(ConcurrentLinkedStack<Integer> stack) {
    Iterator<Integer> it = stack.iterator();
    it.next();
    it.remove();
    it.remove();
  }

  @Test(dataProvider = "empty")
  public void iterator_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    assertThat(stack.iterator().hasNext(), is(false));
  }

  @Test(dataProvider = "populated")
  public void iterator_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    List<Integer> list = new ArrayList<>();
    populate(list, POPULATED_SIZE);
    Collections.reverse(list);
    assertThat(String.format("\nExpected: %s%n     but: %s", stack, list),
        elementsEqual(stack.iterator(), list.iterator()));
  }

  @Test(dataProvider = "empty", expectedExceptions = IllegalStateException.class)
  public void iterator_removalWhenEmpty(ConcurrentLinkedStack<Integer> stack) {
    stack.iterator().remove();
  }

  @Test(dataProvider = "populated")
  public void iterator_removalWhenPopulated(ConcurrentLinkedStack<Integer> stack) {
    Iterator<Integer> it = stack.iterator();
    Integer first = stack.peek();
    it.next();
    it.remove();
    assertThat(stack, not(contains(first)));
    assertThat(stack.size(), is(POPULATED_SIZE - 1));
  }

  @Test(dataProvider = "empty")
  public void serialize_whenEmpty(ConcurrentLinkedStack<Integer> stack) {
    List<Integer> expected = new ArrayList<>(stack);
    List<Integer> actual = new ArrayList<>(SerializableTester.reserialize(stack));
    assertThat(expected, is(equalTo(actual)));
  }

  @Test(dataProvider = "populated")
  public void serialize_whenPopulated(ConcurrentLinkedStack<Integer> stack) {
    List<Integer> expected = new ArrayList<>(stack);
    List<Integer> actual = new ArrayList<>(SerializableTester.reserialize(stack));
    assertThat(expected, is(equalTo(actual)));
  }

  /* ---------------- Stack providers -------------- */

  @DataProvider(name = "empty")
  public Object[][] emptyStack() {
    return new Object[][] {
        { ConcurrentLinkedStack.optimistic() },
        { ConcurrentLinkedStack.linearizable() }};
  }

  @DataProvider(name = "populated")
  public Object[][] populatedStack() {
    return new Object[][] {{ newPopulatedStack(true) }, { newPopulatedStack(false) }};
  }

  ConcurrentLinkedStack<Integer> newPopulatedStack(boolean optimistic) {
    ConcurrentLinkedStack<Integer> stack = optimistic
        ? ConcurrentLinkedStack.optimistic()
        : ConcurrentLinkedStack.linearizable();
    populate(stack, POPULATED_SIZE);
    return stack;
  }

  static void populate(Collection<Integer> collection, int start) {
    for (int i = 0; i < start; i++) {
      collection.add(i);
    }
  }

  /** A listener that validates the internal structure after a successful test execution. */
  public static final class ValidatingStackListener implements IInvokedMethodListener {
    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {}

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
      try {
        if (testResult.isSuccess()) {
          for (Object param : testResult.getParameters()) {
            if (param instanceof ConcurrentLinkedStack<?>) {
              assertThat((ConcurrentLinkedStack<?>) param, is(validate()));
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
      if ((param instanceof ConcurrentLinkedStack<?>)) {
        params[i] = param.getClass().getSimpleName();
      } else {
        params[i] = Objects.toString(param);
      }
    }
  }
}
