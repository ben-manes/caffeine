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
 * A unit-test for the @{@link AbstractLinkedDeque} implementations.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
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
  public void contains_whenFound(Deque<LinkedValue> deque) {
    assertThat(deque.contains(Iterables.get(deque, SIZE / 2)), is(true));
  }

  @Test(dataProvider = "full")
  public void contains_whenNotFound(Deque<LinkedValue> deque) {
    LinkedValue unlinked = new LinkedValue(1);
    assertThat(deque.contains(unlinked), is(false));
  }

  /* ---------------- Move -------------- */

  @Test(dataProvider = "full")
  public void moveToFront_first(LinkedDeque<LinkedValue> deque) {
    checkMoveToFront(deque, deque.getFirst());
  }

  @Test(dataProvider = "full")
  public void moveToFront_middle(LinkedDeque<LinkedValue> deque) {
    checkMoveToFront(deque, Iterables.get(deque, SIZE / 2));
  }

  @Test(dataProvider = "full")
  public void moveToFront_last(LinkedDeque<LinkedValue> deque) {
    checkMoveToFront(deque, deque.getLast());
  }

  private void checkMoveToFront(LinkedDeque<LinkedValue> deque, LinkedValue element) {
    deque.moveToFront(element);
    assertThat(deque.peekFirst(), is(element));
    assertThat(deque.size(), is(SIZE));
  }

  @Test(dataProvider = "full")
  public void moveToBack_first(LinkedDeque<LinkedValue> deque) {
    checkMoveToBack(deque, deque.getFirst());
  }

  @Test(dataProvider = "full")
  public void moveToBack_middle(LinkedDeque<LinkedValue> deque) {
    checkMoveToBack(deque, Iterables.get(deque, SIZE / 2));
  }

  @Test(dataProvider = "full")
  public void moveToBack_last(LinkedDeque<LinkedValue> deque) {
    checkMoveToBack(deque, deque.getLast());
  }

  private void checkMoveToBack(LinkedDeque<LinkedValue> deque, LinkedValue element) {
    deque.moveToBack(element);
    assertThat(deque.size(), is(SIZE));
    assertThat(deque.getLast(), is(element));
  }

  /* ---------------- Peek -------------- */

  @Test(dataProvider = "empty")
  public void peek_whenEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.peek(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void peek_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    LinkedValue first = deque.first;
    assertThat(deque.peek(), is(first));
    assertThat(deque.first, is(first));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(first), is(true));
  }

  @Test(dataProvider = "empty")
  public void peekFirst_whenEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.peekFirst(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void peekFirst_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    LinkedValue first = deque.first;
    assertThat(deque.peekFirst(), is(first));
    assertThat(deque.first, is(first));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(first), is(true));
  }

  @Test(dataProvider = "empty")
  public void peekLast_whenEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.peekLast(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void peekLast_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    LinkedValue last = deque.last;
    assertThat(deque.peekLast(), is(last));
    assertThat(deque.last, is(last));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(last), is(true));
  }

  /* ---------------- Get -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void getFirst_whenEmpty(Deque<LinkedValue> deque) {
    deque.getFirst();
  }

  @Test(dataProvider = "full")
  public void getFirst_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    LinkedValue first = deque.first;
    assertThat(deque.getFirst(), is(first));
    assertThat(deque.first, is(first));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(first), is(true));
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void getLast_whenEmpty(Deque<LinkedValue> deque) {
    deque.getLast();
  }

  @Test(dataProvider = "full")
  public void getLast_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    LinkedValue last = deque.last;
    assertThat(deque.getLast(), is(last));
    assertThat(deque.last, is(last));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(last), is(true));
  }

  /* ---------------- Element -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void element_whenEmpty(Deque<LinkedValue> deque) {
    deque.element();
  }

  @Test(dataProvider = "full")
  public void element_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    LinkedValue first = deque.first;
    assertThat(deque.element(), is(first));
    assertThat(deque.first, is(first));
    assertThat(deque, hasSize(SIZE));
    assertThat(deque.contains(first), is(true));
  }

  /* ---------------- Offer -------------- */

  @Test(dataProvider = "empty")
  public void offer_whenEmpty(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(1);
    assertThat(deque.offer(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void offer_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(SIZE);
    assertThat(deque.offer(value), is(true));
    assertThat(deque.peekFirst(), is(not(value)));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void offer_whenLinked(Deque<LinkedValue> deque) {
    assertThat(deque.offer(deque.peek()), is(false));
    assertThat(deque, hasSize(SIZE));
  }

  @Test(dataProvider = "empty")
  public void offerFirst_whenEmpty(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(1);
    assertThat(deque.offerFirst(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void offerFirst_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(SIZE);
    assertThat(deque.offerFirst(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(not(value)));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void offerFirst_whenLinked(Deque<LinkedValue> deque) {
    assertThat(deque.offerFirst(deque.peek()), is(false));
    assertThat(deque, hasSize(SIZE));
  }

  @Test(dataProvider = "empty")
  public void offerLast_whenEmpty(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(1);
    assertThat(deque.offerLast(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void offerLast_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(SIZE);
    assertThat(deque.offerLast(value), is(true));
    assertThat(deque.peekFirst(), is(not(value)));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void offerLast_whenLinked(Deque<LinkedValue> deque) {
    assertThat(deque.offerLast(deque.peek()), is(false));
    assertThat(deque, hasSize(SIZE));
  }

  /* ---------------- Add -------------- */

  @Test(dataProvider = "empty")
  public void add_whenEmpty(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(1);
    assertThat(deque.add(value), is(true));
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void add_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(SIZE);
    assertThat(deque.add(value), is(true));
    assertThat(deque.peekFirst(), is(not(value)));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full")
  public void add_whenLinked(Deque<LinkedValue> deque) {
    assertThat(deque.add(deque.peek()), is(false));
  }

  @Test(dataProvider = "empty")
  public void addFirst_whenEmpty(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(1);
    deque.addFirst(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void addFirst_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(SIZE);
    deque.addFirst(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(not(value)));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full", expectedExceptions = IllegalArgumentException.class)
  public void addFirst_whenLinked(Deque<LinkedValue> deque) {
    deque.addFirst(deque.peek());
  }

  @Test(dataProvider = "empty")
  public void addLast_whenEmpty(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(1);
    deque.addLast(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void addLast_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(SIZE);
    deque.addLast(value);
    assertThat(deque.peekFirst(), is(not(value)));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full", expectedExceptions = IllegalArgumentException.class)
  public void addLast_whenLinked(Deque<LinkedValue> deque) {
    deque.addLast(deque.peek());
  }

  @Test(dataProvider = "empty")
  public void addAll_withEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.addAll(ImmutableList.<LinkedValue>of()), is(false));
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void addAll_withPopulated(Deque<LinkedValue> deque) {
    List<LinkedValue> expected = new ArrayList<>();
    populate(expected);
    assertThat(deque.addAll(expected), is(true));
    assertThat(Iterables.elementsEqual(deque, expected), is(true));
  }

  @Test(dataProvider = "full")
  public void addAll_withSelf(Deque<LinkedValue> deque) {
    assertThat(deque.addAll(deque), is(false));
  }

  /* ---------------- Poll -------------- */

  @Test(dataProvider = "empty")
  public void poll_whenEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.poll(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void poll_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peek();
    assertThat(deque.poll(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void poll_toEmpty(Deque<LinkedValue> deque) {
    LinkedValue value;
    while ((value = deque.poll()) != null) {
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void pollFirst_whenEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.pollFirst(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void pollFirst_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peekFirst();
    assertThat(deque.pollFirst(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void pollFirst_toEmpty(Deque<LinkedValue> deque) {
    LinkedValue value;
    while ((value = deque.pollFirst()) != null) {
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void pollLast_whenEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.pollLast(), is(nullValue()));
  }

  @Test(dataProvider = "full")
  public void pollLast_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue last = deque.peekLast();
    assertThat(deque.pollLast(), is(last));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(last), is(false));
  }

  @Test(dataProvider = "full")
  public void pollLast_toEmpty(Deque<LinkedValue> deque) {
    LinkedValue value;
    while ((value = deque.pollLast()) != null) {
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  /* ---------------- Remove -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void remove_whenEmpty(Deque<LinkedValue> deque) {
    deque.remove();
  }

  @Test(dataProvider = "full")
  public void remove_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peekFirst();
    assertThat(deque.remove(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void remove_toEmpty(Deque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      LinkedValue value = deque.remove();
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeElement_notFound(Deque<LinkedValue> deque) {
    assertThat(deque.remove(new LinkedValue(0)), is(false));
  }

  @Test(dataProvider = "full")
  public void removeElement_whenFound(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peekFirst();
    assertThat(deque.remove(first), is(true));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeElement_toEmpty(Deque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      LinkedValue value = deque.peek();
      assertThat(deque.remove(value), is(true));
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void removeFirst_whenEmpty(Deque<LinkedValue> deque) {
    deque.removeFirst();
  }

  @Test(dataProvider = "full")
  public void removeFirst_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peekFirst();
    assertThat(deque.removeFirst(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeFirst_toEmpty(Deque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      LinkedValue value = deque.removeFirst();
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void removeLast_whenEmpty(Deque<LinkedValue> deque) {
    deque.removeLast();
  }

  @Test(dataProvider = "full")
  public void removeLast_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue last = deque.peekLast();
    assertThat(deque.removeLast(), is(last));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(last), is(false));
  }

  @Test(dataProvider = "full")
  public void removeLast_toEmpty(Deque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      LinkedValue value = deque.removeLast();
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeFirstOccurrence_notFound(Deque<LinkedValue> deque) {
    assertThat(deque.removeFirstOccurrence(new LinkedValue(0)), is(false));
  }

  @Test(dataProvider = "full")
  public void removeFirstOccurrence_whenFound(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peekFirst();
    assertThat(deque.removeFirstOccurrence(first), is(true));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeFirstOccurrence_toEmpty(Deque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      LinkedValue value = deque.peek();
      assertThat(deque.removeFirstOccurrence(value), is(true));
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeLastOccurrence_notFound(Deque<LinkedValue> deque) {
    assertThat(deque.removeLastOccurrence(new LinkedValue(0)), is(false));
  }

  @Test(dataProvider = "full")
  public void removeLastOccurrence_whenFound(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peekFirst();
    assertThat(deque.removeLastOccurrence(first), is(true));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeLastOccurrence_toEmpty(Deque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      LinkedValue value = deque.peek();
      assertThat(deque.removeLastOccurrence(value), is(true));
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "empty")
  public void removeAll_withEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.removeAll(ImmutableList.of()), is(false));
    assertThat(deque, is(deeplyEmpty()));
  }

  @Test(dataProvider = "full")
  public void remove_withPopulated(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peekFirst();
    assertThat(deque.removeAll(ImmutableList.of(first)), is(true));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void removeAll_toEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.removeAll(ImmutableList.copyOf(deque)), is(true));
    assertThat(deque, is(deeplyEmpty()));
  }

  /* ---------------- Stack -------------- */

  @Test(dataProvider = "empty")
  public void push_whenEmpty(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(1);
    deque.push(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(value));
    assertThat(deque, hasSize(1));
  }

  @Test(dataProvider = "full")
  public void push_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue value = new LinkedValue(SIZE);
    deque.push(value);
    assertThat(deque.peekFirst(), is(value));
    assertThat(deque.peekLast(), is(not(value)));
    assertThat(deque, hasSize(SIZE + 1));
  }

  @Test(dataProvider = "full", expectedExceptions = IllegalArgumentException.class)
  public void push_whenLinked(Deque<LinkedValue> deque) {
    deque.push(deque.peek());
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void pop_whenEmpty(Deque<LinkedValue> deque) {
    deque.pop();
  }

  @Test(dataProvider = "full")
  public void pop_whenPopulated(Deque<LinkedValue> deque) {
    LinkedValue first = deque.peekFirst();
    assertThat(deque.pop(), is(first));
    assertThat(deque, hasSize(SIZE - 1));
    assertThat(deque.contains(first), is(false));
  }

  @Test(dataProvider = "full")
  public void pop_toEmpty(Deque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      LinkedValue value = deque.pop();
      assertThat(deque.contains(value), is(false));
    }
    assertThat(deque, is(deeplyEmpty()));
  }

  /* ---------------- Iterators -------------- */

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void iterator_noMoreElements(Deque<LinkedValue> deque) {
    deque.iterator().next();
  }

  @Test(dataProvider = "empty")
  public void iterator_whenEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.iterator().hasNext(), is(false));
  }

  @Test(dataProvider = "full")
  public void iterator_whenWarmed(Deque<LinkedValue> deque) {
    List<LinkedValue> expected = new ArrayList<>();
    populate(expected);

    assertThat(elementsEqual(deque.iterator(), expected.iterator()), is(true));
  }

  @Test(dataProvider = "full", expectedExceptions = UnsupportedOperationException.class)
  public void iterator_removal(Deque<LinkedValue> deque) {
    deque.iterator().remove();
  }

  @Test(dataProvider = "empty", expectedExceptions = NoSuchElementException.class)
  public void descendingIterator_noMoreElements(Deque<LinkedValue> deque) {
    deque.descendingIterator().next();
  }

  @Test(dataProvider = "empty")
  public void descendingIterator_whenEmpty(Deque<LinkedValue> deque) {
    assertThat(deque.descendingIterator().hasNext(), is(false));
  }

  @Test(dataProvider = "full")
  public void descendingIterator_whenWarmed(Deque<LinkedValue> deque) {
    List<LinkedValue> expected = new ArrayList<>();
    populate(expected);
    Collections.reverse(expected);

    assertThat(elementsEqual(deque.descendingIterator(), expected.iterator()), is(true));
  }

  @Test(dataProvider = "full", expectedExceptions = UnsupportedOperationException.class)
  public void descendingIterator_removal(Deque<LinkedValue> deque) {
    deque.descendingIterator().remove();
  }

  /* ---------------- Deque providers -------------- */

  @DataProvider(name = "empty")
  public Object[][] providesEmptyDeque() {
    return new Object[][] {
        { new AccessOrderDeque<LinkedValue>() },
        { new WriteOrderDeque<LinkedValue>() },
    };
  }

  @DataProvider(name = "full")
  public Object[][] providesWarmedDeque() {
    Deque<LinkedValue> accessOrder = new AccessOrderDeque<LinkedValue>();
    Deque<LinkedValue> writeOrder = new WriteOrderDeque<LinkedValue>();
    populate(accessOrder);
    populate(writeOrder);
    return new Object[][] { { accessOrder }, { writeOrder }};
  }

  void populate(Collection<LinkedValue> collection) {
    for (int i = 0; i < SIZE; i++) {
      collection.add(new LinkedValue(i));
    }
  }

  static final class LinkedValue implements AccessOrder<LinkedValue>, WriteOrder<LinkedValue> {
    LinkedValue prev;
    LinkedValue next;
    final int value;

    LinkedValue(int value) {
      this.value = value;
    }

    @Override
    public LinkedValue getPreviousInAccessOrder() {
      return prev;
    }

    @Override
    public void setPreviousInAccessOrder(LinkedValue prev) {
      this.prev = prev;
    }

    @Override
    public LinkedValue getNextInAccessOrder() {
      return next;
    }

    @Override
    public void setNextInAccessOrder(LinkedValue next) {
      this.next = next;
    }

    @Override
    public LinkedValue getPreviousInWriteOrder() {
      return prev;
    }

    @Override
    public void setPreviousInWriteOrder(LinkedValue prev) {
      this.prev = prev;
    }

    @Override
    public LinkedValue getNextInWriteOrder() {
      return next;
    }

    @Override
    public void setNextInWriteOrder(LinkedValue next) {
      this.next = next;
    }

    @Override
    public boolean equals(Object o) {
      return (o instanceof LinkedValue) && (value == ((LinkedValue) o).value);
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
