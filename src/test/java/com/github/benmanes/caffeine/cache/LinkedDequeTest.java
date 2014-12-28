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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.matchers.IsEmptyIterable.deeplyEmpty;
import static com.google.common.collect.Iterators.elementsEqual;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * A unit-test for {@link LinkedDeque} methods.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(groups = "development")
public final class LinkedDequeTest {
  static final int SIZE = 100;

  @Test(dataProvider = "empty")
  public void clear_whenEmpty(Deque<?> deque) {
    deque.clear();
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "full")
  public void clear_whenPopulated(Deque<?> deque) {
    deque.clear();
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void isEmpty_whenEmpty(Deque<?> deque) {
    assertThat(deque.isEmpty(), is(true));
  }

  @Test(dataProvider = "full")
  public void isEmpty_whenPopulated(Deque<?> deque) {
    assertThat(deque.isEmpty(), is(false));
  }

  @Test(dataProvider = "empty")
  public void size_whenEmpty(Deque<?> deque) {
    assertThat(deque.size(), is(0));
  }

  @Test(dataProvider = "full")
  public void size_whenPopulated(Deque<?> deque) {
    assertThat(deque.size(), is(SIZE));
    assertThat(Iterables.size(deque), is(SIZE));
  }

  @Test(dataProvider = "empty")
  public void contains_withNull(Deque<?> deque) {
    assertThat(deque.contains(null), is(false));
  }

  @Test(dataProvider = "full")
  public void contains_whenFound(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.contains(Iterables.get(deque, SIZE / 2)), is(true));
  }

  @Test(dataProvider = "full")
  public void contains_whenNotFound(LinkedDeque<SimpleLinkedValue> deque) {
    SimpleLinkedValue unlinked = new SimpleLinkedValue(1);
    assertThat(deque.contains(unlinked), is(false));
  }

  /* ---------------- Move -------------- */

  @Test(dataProvider = "full")
  public void moveToFront_first(LinkedDeque<SimpleLinkedValue> deque) {
    checkMoveToFront(deque, deque.getFirst());
  }

  @Test(dataProvider = "full")
  public void moveToFront_middle(LinkedDeque<SimpleLinkedValue> deque) {
    checkMoveToFront(deque, Iterables.get(deque, SIZE / 2));
  }

  @Test(dataProvider = "full")
  public void moveToFront_last(LinkedDeque<SimpleLinkedValue> deque) {
    checkMoveToFront(deque, deque.getLast());
  }

  private void checkMoveToFront(LinkedDeque<SimpleLinkedValue> deque, SimpleLinkedValue element) {
    deque.moveToFront(element);
    assertThat(deque.peekFirst(), is(element));
    assertThat(deque.size(), is(SIZE));
  }

  @Test(dataProvider = "full")
  public void moveToBack_first(LinkedDeque<SimpleLinkedValue> deque) {
    checkMoveToBack(deque, deque.getFirst());
  }

  @Test(dataProvider = "full")
  public void moveToBack_middle(LinkedDeque<SimpleLinkedValue> deque) {
    checkMoveToBack(deque, Iterables.get(deque, SIZE / 2));
  }

  @Test(dataProvider = "full")
  public void moveToBack_last(LinkedDeque<SimpleLinkedValue> deque) {
    checkMoveToBack(deque, deque.getLast());
  }

  private void checkMoveToBack(LinkedDeque<SimpleLinkedValue> deque, SimpleLinkedValue element) {
    deque.moveToBack(element);
    assertThat(deque.size(), is(SIZE));
    assertThat(deque.getLast(), is(element));
  }

  /* ---------------- Peek -------------- */

  @Test(dataProvider = "empty")
  public void peek_whenEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.peek(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void peek_whenPopulated(LinkedDeque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.first;
    assertThat(deque.peek(), is(first));
    assertThat(deque.first, is(first));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(first), is(true));
  }

  @Test(dataProvider = "empty")
  public void peekFirst_whenEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.peekFirst(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void peekFirst_whenPopulated(LinkedDeque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.first;
    assertThat(deque.peekFirst(), is(first));
    assertThat(deque.first, is(first));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(first), is(true));
  }

  @Test(dataProvider = "empty")
  public void peekLast_whenEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.peekLast(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void peekLast_whenPopulated(LinkedDeque<SimpleLinkedValue> deque) {
    SimpleLinkedValue last = deque.last;
    assertThat(deque.peekLast(), is(last));
    assertThat(deque.last, is(last));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(last), is(true));
  }

  /* ---------------- Get -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void getFirst_whenEmpty(Deque<SimpleLinkedValue> deque) {
    deque.getFirst();
  }

  @Test(dataProvider = "full")
  public void getFirst_whenPopulated(LinkedDeque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.first;
    assertThat(deque.getFirst(), is(first));
    assertThat(deque.first, is(first));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(first), is(true));
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void getLast_whenEmpty(Deque<SimpleLinkedValue> deque) {
    deque.getLast();
  }

  @Test(dataProvider = "full")
  public void getLast_whenPopulated(LinkedDeque<SimpleLinkedValue> deque) {
    SimpleLinkedValue last = deque.last;
    assertThat(deque.getLast(), is(last));
    assertThat(deque.last, is(last));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(last), is(true));
  }

  /* ---------------- Element -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void element_whenEmpty(Deque<SimpleLinkedValue> deque) {
    deque.element();
  }

  @Test(dataProvider = "full")
  public void element_whenPopulated(LinkedDeque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.first;
    assertThat(deque.element(), is(first));
    assertThat(deque.first, is(first));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(first), is(true));
  }

  /* ---------------- Offer -------------- */

  @Test(dataProvider = "empty")
  public void offer_whenEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(1);
    assertThat(deque.offer(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void offer_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(SIZE);
    assertThat(deque.offer(value), is(true));
    assertThat(deque.peekFirst(), is(not(value)));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void offer_whenLinked(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.offer(deque.peek()), is(false));
    assertThat(deque, hasSize(SIZE));
  }

  @Test(dataProvider = "empty")
  public void offerFirst_whenEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(1);
    assertThat(deque.offerFirst(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void offerFirst_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(SIZE);
    assertThat(deque.offerFirst(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(not(value)));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void offerFirst_whenLinked(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.offerFirst(deque.peek()), is(false));
    assertThat(deque, hasSize(SIZE));
  }

  @Test(dataProvider = "empty")
  public void offerLast_whenEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(1);
    assertThat(deque.offerLast(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void offerLast_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(SIZE);
    assertThat(deque.offerLast(value), is(true));
    assertThat(deque.peekFirst(), is(not(value)));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void offerLast_whenLinked(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.offerLast(deque.peek()), is(false));
    assertThat(deque, hasSize(SIZE));
  }

  /* ---------------- Add -------------- */

  @Test(dataProvider = "empty")
  public void add_whenEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(1);
    assertThat(deque.add(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void add_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(SIZE);
    assertThat(deque.add(value), is(true));
    assertThat(deque.peekFirst(), is(not(value)));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void add_whenLinked(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.add(deque.peek()), is(false));
  }

  @Test(dataProvider = "empty")
  public void addFirst_whenEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(1);
    deque.addFirst(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void addFirst_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(SIZE);
    deque.addFirst(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(not(value)));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full", expectedExceptions = IllegalArgumentException.class)
  public void addFirst_whenLinked(Deque<SimpleLinkedValue> deque) {
    deque.addFirst(deque.peek());
  }

  @Test(dataProvider = "empty")
  public void addLast_whenEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(1);
    deque.addLast(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void addLast_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(SIZE);
    deque.addLast(value);
    assertThat(deque.peekFirst(), is(not(value)));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full", expectedExceptions = IllegalArgumentException.class)
  public void addLast_whenLinked(Deque<SimpleLinkedValue> deque) {
    deque.addLast(deque.peek());
  }

  @Test(dataProvider = "empty")
  public void addAll_withEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.addAll(ImmutableList.<SimpleLinkedValue>of()), is(false));
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void addAll_withPopulated(Deque<SimpleLinkedValue> deque) {
    List<SimpleLinkedValue> expected = new ArrayList<>();
    warmUp(expected);
    assertThat(deque.addAll(expected), is(true));
    assertThat(Iterables.elementsEqual(deque, expected), is(true));
  }

  @Test(dataProvider = "full")
  public void addAll_withSelf(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.addAll(deque), is(false));
  }

  /* ---------------- Poll -------------- */

  @Test(dataProvider = "empty")
  public void poll_whenEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.poll(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void poll_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peek();
    assertThat(deque.poll(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void poll_toEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value;
    while ((value = deque.poll()) != null) {
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void pollFirst_whenEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.pollFirst(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void pollFirst_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peekFirst();
    assertThat(deque.pollFirst(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void pollFirst_toEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value;
    while ((value = deque.pollFirst()) != null) {
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void pollLast_whenEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.pollLast(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void pollLast_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue last = deque.peekLast();
    assertThat(deque.pollLast(), is(last));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(last), is(false));
  }

  @Test(dataProvider = "full")
  public void pollLast_toEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value;
    while ((value = deque.pollLast()) != null) {
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  /* ---------------- Remove -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void remove_whenEmpty(Deque<SimpleLinkedValue> deque) {
    deque.remove();
  }

  @Test(dataProvider = "full")
  public void remove_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peekFirst();
    assertThat(deque.remove(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void remove_toEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    while (!deque.isEmpty()) {
      SimpleLinkedValue value = deque.remove();
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeElement_notFound(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.remove(new SimpleLinkedValue(0)), is(false));
  }

  @Test(dataProvider = "full")
  public void removeElement_whenFound(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peekFirst();
    assertThat(deque.remove(first), is(true));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeElement_toEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    while (!deque.isEmpty()) {
      SimpleLinkedValue value = deque.peek();
      assertThat(deque.remove(value), is(true));
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void removeFirst_whenEmpty(Deque<SimpleLinkedValue> deque) {
    deque.removeFirst();
  }

  @Test(dataProvider = "full")
  public void removeFirst_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peekFirst();
    assertThat(deque.removeFirst(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeFirst_toEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    while (!deque.isEmpty()) {
      SimpleLinkedValue value = deque.removeFirst();
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void removeLast_whenEmpty(Deque<SimpleLinkedValue> deque) {
    deque.removeLast();
  }

  @Test(dataProvider = "full")
  public void removeLast_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue last = deque.peekLast();
    assertThat(deque.removeLast(), is(last));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(last), is(false));
  }

  @Test(dataProvider = "full")
  public void removeLast_toEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    while (!deque.isEmpty()) {
      SimpleLinkedValue value = deque.removeLast();
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeFirstOccurrence_notFound(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.removeFirstOccurrence(new SimpleLinkedValue(0)), is(false));
  }

  @Test(dataProvider = "full")
  public void removeFirstOccurrence_whenFound(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peekFirst();
    assertThat(deque.removeFirstOccurrence(first), is(true));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeFirstOccurrence_toEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    while (!deque.isEmpty()) {
      SimpleLinkedValue value = deque.peek();
      assertThat(deque.removeFirstOccurrence(value), is(true));
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeLastOccurrence_notFound(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.removeLastOccurrence(new SimpleLinkedValue(0)), is(false));
  }

  @Test(dataProvider = "full")
  public void removeLastOccurrence_whenFound(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peekFirst();
    assertThat(deque.removeLastOccurrence(first), is(true));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeLastOccurrence_toEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    while (!deque.isEmpty()) {
      SimpleLinkedValue value = deque.peek();
      assertThat(deque.removeLastOccurrence(value), is(true));
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeAll_withEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.removeAll(ImmutableList.of()), is(false));
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "full")
  public void remove_withPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peekFirst();
    assertThat(deque.removeAll(ImmutableList.of(first)), is(true));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeAll_toEmpty(Deque<SimpleLinkedValue> deque) {
    assertThat(deque.removeAll(ImmutableList.copyOf(deque)), is(true));
    assertThat(deque, is(deeplyEmpty()));
  }

  /* ---------------- Stack -------------- */

  @Test(dataProvider = "empty")
  public void push_whenEmpty(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(1);
    deque.push(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void push_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue value = new SimpleLinkedValue(SIZE);
    deque.push(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(not(value)));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full", expectedExceptions = IllegalArgumentException.class)
  public void push_whenLinked(Deque<SimpleLinkedValue> deque) {
    deque.push(deque.peek());
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void pop_whenEmpty(Deque<SimpleLinkedValue> deque) {
    deque.pop();
  }

  @Test(dataProvider = "full")
  public void pop_whenPopulated(Deque<SimpleLinkedValue> deque) {
    SimpleLinkedValue first = deque.peekFirst();
    assertThat(deque.pop(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void pop_toEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    while (!deque.isEmpty()) {
      SimpleLinkedValue value = deque.pop();
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  /* ---------------- Iterators -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void iterator_noMoreElements(LinkedDeque<SimpleLinkedValue> deque) {
    deque.iterator().next();
  }

  @Test(dataProvider = "empty")
  public void iterator_whenEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    assertThat(deque.iterator().hasNext(), is(false));
  }

  @Test(dataProvider = "full")
  public void iterator_whenWarmed(LinkedDeque<SimpleLinkedValue> deque) {
    List<SimpleLinkedValue> expected = new ArrayList<>();
    warmUp(expected);

    assertThat(elementsEqual(deque.iterator(), expected.iterator()), is(true));
  }

  @Test(dataProvider = "full", expectedExceptions = UnsupportedOperationException.class)
  public void iterator_removal(LinkedDeque<SimpleLinkedValue> deque) {
    deque.iterator().remove();
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void descendingIterator_noMoreElements(LinkedDeque<SimpleLinkedValue> deque) {
    deque.descendingIterator().next();
  }

  @Test(dataProvider = "empty")
  public void descendingIterator_whenEmpty(LinkedDeque<SimpleLinkedValue> deque) {
    assertThat(deque.descendingIterator().hasNext(), is(false));
  }

  @Test(dataProvider = "full")
  public void descendingIterator_whenWarmed(LinkedDeque<SimpleLinkedValue> deque) {
    List<SimpleLinkedValue> expected = new ArrayList<>();
    warmUp(expected);
    Collections.reverse(expected);

    assertThat(elementsEqual(deque.descendingIterator(), expected.iterator()), is(true));
  }

  @Test(dataProvider = "full", expectedExceptions = UnsupportedOperationException.class)
  public void descendingIterator_removal(LinkedDeque<SimpleLinkedValue> deque) {
    deque.descendingIterator().remove();
  }

  /* ---------------- Deque providers -------------- */

  @DataProvider(name = "empty")
  public Object[][] providesEmptyDeque() {
    return new Object[][] {{
      new LinkedDeque<SimpleLinkedValue>()
    }};
  }

  @DataProvider(name = "full")
  public Object[][] providesWarmedDeque() {
    LinkedDeque<SimpleLinkedValue> deque = new LinkedDeque<SimpleLinkedValue>();
    warmUp(deque);
    return new Object[][] {{ deque }};
  }

  void warmUp(Collection<SimpleLinkedValue> collection) {
    for (int i = 0; i < SIZE; i++) {
      collection.add(new SimpleLinkedValue(i));
    }
  }

  static final class SimpleLinkedValue implements Linked<SimpleLinkedValue> {
    SimpleLinkedValue prev;
    SimpleLinkedValue next;
    final int value;

    SimpleLinkedValue(int value) {
      this.value = value;
    }

    @Override
    public SimpleLinkedValue getPrevious() {
      return prev;
    }

    @Override
    public void setPrevious(SimpleLinkedValue prev) {
      this.prev = prev;
    }

    @Override
    public SimpleLinkedValue getNext() {
      return next;
    }

    @Override
    public void setNext(SimpleLinkedValue next) {
      this.next = next;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof SimpleLinkedValue)) {
        return false;
      }
      return value == ((SimpleLinkedValue) o).value;
    }

    @Override
    public int hashCode() {
      return value;
    }

    @Override
    public String toString() {
      return String.format("value=%s prev=%s, next=%s]", value,
          (prev == null) ? null : prev.value,
          (next == null) ? null : next.value);
    }
  }
}
