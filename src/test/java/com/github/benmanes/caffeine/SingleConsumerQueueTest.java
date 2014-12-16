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

import static com.github.benmanes.caffeine.matchers.IsEmptyCollection.emptyCollection;
import static com.google.common.collect.Iterators.elementsEqual;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class SingleConsumerQueueTest {
  private static final int POPULATED_SIZE = 5;

  @Test(dataProvider = "empty")
  public void clear_whenEmpty(Queue<?> queue) {
    queue.clear();
    assertThat(queue, is(emptyCollection()));
  }

  @Test(dataProvider = "populated")
  public void clear_whenPopulated(Queue<?> queue) {
    queue.clear();
    assertThat(queue, is(emptyCollection()));
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
  }

  @Test(dataProvider = "populated")
  public void size_whenPopulated(Queue<?> queue) {
    assertThat(queue.size(), is(POPULATED_SIZE));
    assertThat(Iterables.size(queue), is(POPULATED_SIZE));
  }

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

  /* ---------------- Peek -------------- */

  @Test(dataProvider = "empty")
  public void peek_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.peek(), is(nullValue()));
  }

  @Test(dataProvider = "populated")
  public void peek_whenPopulated(SingleConsumerQueue<Integer> queue) {
    Integer first = queue.tail.next.value;
    assertThat(queue.peek(), is(first));
    assertThat(queue, hasSize(POPULATED_SIZE));
    assertThat(queue.contains(first), is(true));
  }

  /* ---------------- Element -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void element_whenEmpty(Queue<Integer> queue) {
    queue.element();
  }

  @Test(dataProvider = "populated")
  public void element_whenPopulated(SingleConsumerQueue<Integer> queue) {
    Integer first = queue.tail.next.value;
    assertThat(queue.element(), is(first));
    assertThat(queue, hasSize(POPULATED_SIZE));
    assertThat(queue.contains(first), is(true));
  }

  /* ---------------- Offer -------------- */

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

  /* ---------------- Add -------------- */

  @Test(dataProvider = "empty")
  public void add_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.add(1), is(true));
    assertThat(queue.peek(), is(1));
    assertThat(Iterables.getLast(queue), is(1));
    assertThat(queue, hasSize(1));
  }

  @Test(dataProvider = "populated")
  public void add_whenPopulated(Queue<Integer> queue) {
    assertThat(queue.add(-1), is(true));
    assertThat(queue.peek(), is(not(-1)));
    assertThat(Iterables.getLast(queue), is(-1));
    assertThat(queue, hasSize(POPULATED_SIZE + 1));
  }

  /* ---------------- Poll -------------- */

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
    assertThat(queue, is(emptyCollection()));
  }

  /* ---------------- Remove -------------- */

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
    assertThat(queue, is(emptyCollection()));
  }

  @Test(dataProvider = "empty,populated")
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
    assertThat(queue, is(emptyCollection()));
  }

  @Test(dataProvider = "empty")
  public void removeAll_withEmpty(Queue<Integer> queue) {
    assertThat(queue.removeAll(ImmutableList.of()), is(false));
    assertThat(queue, is(emptyCollection()));
  }

  @Test(dataProvider = "populated")
  public void removeAll_withPopulated(Queue<Integer> queue) {
    Integer first = queue.peek();
    assertThat(queue.removeAll(ImmutableList.of(first)), is(true));
    assertThat(queue, hasSize(POPULATED_SIZE - 1));
    assertThat(queue.contains(first), is(false));
  }

  @Test(enabled = false, dataProvider = "populated")
  public void removeAll_toEmpty(Queue<Integer> queue) {
    assertThat(queue.removeAll(ImmutableList.copyOf(queue)), is(true));
    assertThat(queue, is(emptyCollection()));
  }

  /* ---------------- Retain -------------- */

  @Test(dataProvider = "empty")
  public void retainAll_withEmpty(Queue<Integer> queue) {
    assertThat(queue.retainAll(ImmutableList.of()), is(false));
    assertThat(queue, is(emptyCollection()));
  }

  @Test(enabled = false, dataProvider = "populated")
  public void retainAll_withPopulated(Queue<Integer> queue) {
    Integer first = queue.peek();
    assertThat(queue.retainAll(ImmutableList.of(first)), is(true));
    assertThat(queue, hasSize(1));
    assertThat(queue.contains(first), is(true));
  }

  @Test(enabled = false, dataProvider = "populated")
  public void retainAll_toEmpty(Queue<Integer> queue) {
    assertThat(queue.retainAll(ImmutableList.of()), is(true));
    assertThat(queue, is(emptyCollection()));
  }

  /* ---------------- Iterators -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void iterator_noMoreElements(Queue<Integer> queue) {
    queue.iterator().next();
  }

  @Test(dataProvider = "empty")
  public void iterator_whenEmpty(Queue<Integer> queue) {
    assertThat(queue.iterator().hasNext(), is(false));
  }

  @Test(dataProvider = "populated")
  public void iterator_whenPopulated(Queue<Integer> queue) {
    List<Integer> copy = new ArrayList<>();
    populate(copy);
    assertThat(elementsEqual(queue.iterator(), copy.iterator()), is(true));
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
    assertThat(queue, is(emptyCollection()));
  }

  /* ---------------- toArray -------------- */

  /* ---------------- toString -------------- */

  /* ---------------- Queue providers -------------- */

  @DataProvider
  public Object[][] empty() {
    return new Object[][] {{ new SingleConsumerQueue<Integer>() }};
  }

  @DataProvider(name = "populated")
  public Object[][] providesPopulated() {
    return new Object[][] {{ makePopulated() }};
  }

  @DataProvider(name = "empty,populated")
  public Object[][] providesEmptyAndPopulated() {
    return new Object[][] { { new SingleConsumerQueue<Integer>() }, { makePopulated() }};
  }

  static SingleConsumerQueue<Integer> makePopulated() {
    SingleConsumerQueue<Integer> queue = new SingleConsumerQueue<>();
    populate(queue);
    return queue;
  }

  static void populate(Collection<Integer> collection) {
    for (int i = 0; i < POPULATED_SIZE; i++) {
      collection.add(i);
    }
  }
}
