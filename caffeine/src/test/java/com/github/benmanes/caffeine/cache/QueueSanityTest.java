/*
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.hamcrest.Matcher;
import org.jctools.util.Pow2;
import org.junit.Before;
import org.junit.Test;

/**
 * @author nitsanw@yahoo.com (Nitsan Wakart)
 */
@SuppressWarnings({"deprecation", "PreferJavaTimeOverload", "ThreadPriorityCheck"})
public abstract class QueueSanityTest {

  public static final int SIZE = 8192 * 2;

  private final Queue<Integer> queue;
  private final Ordering ordering;
  private final boolean isBounded;
  private final int capacity;

  protected QueueSanityTest(Queue<Integer> queue,
      Ordering ordering, int capacity, boolean isBounded) {
    this.queue = queue;
    this.ordering = ordering;
    this.capacity = capacity;
    this.isBounded = isBounded;
  }

  @Before
  public void clear() {
    queue.clear();
  }

  @Test
  public void sanity() {
    for (int i = 0; i < SIZE; i++) {
      assertNull(queue.poll());
      assertThat(queue, emptyAndZeroSize());
    }
    int i = 0;
    while (i < SIZE && queue.offer(i)) {
      i++;
    }
    int size = i;
    assertEquals(size, queue.size());
    if (ordering == Ordering.FIFO) {
      // expect FIFO
      i = 0;
      Integer p;
      Integer e;
      while ((p = queue.peek()) != null) {
        e = queue.poll();
        assertEquals(p, e);
        assertEquals(size - (i + 1), queue.size());
        assertEquals(i++, e.intValue());
      }
      assertEquals(size, i);
    } else {
      // expect sum of elements is (size - 1) * size / 2 = 0 + 1 + .... + (size - 1)
      int sum = (size - 1) * size / 2;
      i = 0;
      Integer e;
      while ((e = queue.poll()) != null) {
        assertEquals(--size, queue.size());
        sum -= e;
      }
      assertEquals(0, sum);
    }
    assertNull(queue.poll());
    assertThat(queue, emptyAndZeroSize());
  }

  @Test
  public void testSizeIsTheNumberOfOffers() {
    int currentSize = 0;
    while (currentSize < SIZE && queue.offer(currentSize)) {
      currentSize++;
      assertThat(queue, hasSize(currentSize));
    }
  }

  @Test
  public void whenFirstInThenFirstOut() {
    assumeThat(ordering, is(Ordering.FIFO));

    // Arrange
    int i = 0;
    while (i < SIZE && queue.offer(i)) {
      i++;
    }
    final int size = queue.size();

    // Act
    i = 0;
    Integer prev;
    while ((prev = queue.peek()) != null) {
      final Integer item = queue.poll();

      assertThat(item, is(prev));
      assertThat(queue, hasSize(size - (i + 1)));
      assertThat(item, is(i));
      i++;
    }

    // Assert
    assertThat(i, is(size));
  }

  @Test(expected = NullPointerException.class)
  public void offerNullResultsInNPE() {
    queue.offer(null);
  }

  @Test
  public void whenOfferItemAndPollItemThenSameInstanceReturnedAndQueueIsEmpty() {
    assertThat(queue, emptyAndZeroSize());

    // Act
    final Integer e = 1876876;
    queue.offer(e);
    assertFalse(queue.isEmpty());
    assertEquals(1, queue.size());

    final Integer oh = queue.poll();
    assertEquals(e, oh);

    // Assert
    assertThat(oh, sameInstance(e));
    assertThat(queue, emptyAndZeroSize());
  }

  @Test
  public void testPowerOf2Capacity() {
    assumeThat(isBounded, is(true));
    int n = Pow2.roundToPowerOfTwo(capacity);

    for (int i = 0; i < n; i++) {
      assertTrue("Failed to insert:" + i, queue.offer(i));
    }
    assertFalse(queue.offer(n));
  }

  static final class Val {
    public int value;
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void testHappensBefore() throws InterruptedException {
    final AtomicBoolean stop = new AtomicBoolean();
    final Queue q = queue;
    final Val fail = new Val();
    Thread t1 = new Thread(new Runnable() {
      @Override public void run() {
        while (!stop.get()) {
          for (int i = 1; i <= 10; i++) {
            Val v = new Val();
            v.value = i;
            q.offer(v);
          }
          // slow down the producer, this will make the queue mostly empty encouraging visibility
          // issues.
          Thread.yield();
        }
      }
    });
    Thread t2 = new Thread(new Runnable() {
      @Override
      public void run() {
        while (!stop.get()) {
          for (int i = 0; i < 10; i++) {
            Val v = (Val) q.peek();
            if (v != null && v.value == 0) {
              fail.value = 1;
              stop.set(true);
            }
            q.poll();
          }
        }
      }
    });

    t1.start();
    t2.start();
    Thread.sleep(1000);
    stop.set(true);
    t1.join();
    t2.join();
    assertEquals("reordering detected", 0, fail.value);

  }

  @Test
  public void testSize() throws InterruptedException {
    final AtomicBoolean stop = new AtomicBoolean();
    final Queue<Integer> q = queue;
    final Val fail = new Val();
    Thread t1 = new Thread(new Runnable() {
      @Override
      public void run() {
        while (!stop.get()) {
          q.offer(1);
          q.poll();
        }
      }
    });
    Thread t2 = new Thread(new Runnable() {
      @Override
      public void run() {
        while (!stop.get()) {
          int size = q.size();
          if (size != 0 && size != 1) {
            fail.value++;
          }
        }
      }
    });

    t1.start();
    t2.start();
    Thread.sleep(1000);
    stop.set(true);
    t1.join();
    t2.join();
    assertEquals("Unexpected size observed", 0, fail.value);

  }

  @Test
  public void testPollAfterIsEmpty() throws InterruptedException {
    final AtomicBoolean stop = new AtomicBoolean();
    final Queue<Integer> q = queue;
    final Val fail = new Val();
    Thread t1 = new Thread(new Runnable() {
      @Override
      public void run() {
        while (!stop.get()) {
          q.offer(1);
          // slow down the producer, this will make the queue mostly empty encouraging visibility
          // issues.
          Thread.yield();
        }
      }
    });
    Thread t2 = new Thread(new Runnable() {
      @Override
      public void run() {
        while (!stop.get()) {
          if (!q.isEmpty() && q.poll() == null) {
            fail.value++;
          }
        }
      }
    });

    t1.start();
    t2.start();
    Thread.sleep(1000);
    stop.set(true);
    t1.join();
    t2.join();
    assertEquals("Observed no element in non-empty queue", 0, fail.value);

  }

  public static Matcher<Collection<?>> emptyAndZeroSize() {
    return allOf(hasSize(0), empty());
  }

  enum Ordering {
    FIFO
  }
}
