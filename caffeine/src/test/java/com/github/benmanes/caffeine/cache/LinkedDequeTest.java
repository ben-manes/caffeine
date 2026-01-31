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

import static com.github.benmanes.caffeine.testing.CollectionSubject.assertThat;
import static com.google.common.collect.Iterators.elementsEqual;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.benmanes.caffeine.cache.AccessOrderDeque.AccessOrder;
import com.github.benmanes.caffeine.cache.LinkedDeque.PeekingIterator;
import com.github.benmanes.caffeine.cache.WriteOrderDeque.WriteOrder;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.errorprone.annotations.Var;

/**
 * A unit-test for the @{@link AbstractLinkedDeque} implementations.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"ClassEscapesDefinedScope", "PMD.LooseCoupling"})
final class LinkedDequeTest {
  static final int SIZE = 100;

  @ParameterizedTest @MethodSource("empty")
  void clear_whenEmpty(Deque<?> deque) {
    deque.clear();
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("full")
  void clear_whenPopulated(Deque<?> deque) {
    deque.clear();
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void isEmpty_whenEmpty(Deque<?> deque) {
    assertThat(deque.isEmpty()).isTrue();
  }

  @ParameterizedTest @MethodSource("full")
  void isEmpty_whenPopulated(Deque<?> deque) {
    assertThat(deque.isEmpty()).isFalse();
  }

  @ParameterizedTest @MethodSource("empty")
  void size_whenEmpty(Deque<?> deque) {
    assertThat(deque.size()).isEqualTo(0);
  }

  @ParameterizedTest @MethodSource("full")
  void size_whenPopulated(Deque<?> deque) {
    assertThat(deque.size()).isEqualTo(SIZE);
  }

  @ParameterizedTest @MethodSource("empty")
  void contains_withNull(Deque<?> deque) {
    assertThat(deque.contains(null)).isFalse();
  }

  @ParameterizedTest @MethodSource("full")
  void contains_whenFound(LinkedDeque<LinkedValue> deque) {
    int index = ThreadLocalRandom.current().nextInt(deque.size());
    assertThat(deque.contains(Iterables.get(deque, index))).isTrue();
  }

  @ParameterizedTest @MethodSource("full")
  void contains_whenNotFound(LinkedDeque<LinkedValue> deque) {
    var unlinked = new LinkedValue(1);
    assertThat(deque.contains(unlinked)).isFalse();
  }

  /* --------------- Move --------------- */

  @ParameterizedTest @MethodSource("full")
  void moveToFront_first(LinkedDeque<LinkedValue> deque) {
    checkMoveToFront(deque, deque.getFirst());
  }

  @ParameterizedTest @MethodSource("full")
  void moveToFront_middle(LinkedDeque<LinkedValue> deque) {
    checkMoveToFront(deque, Iterables.get(deque, SIZE / 2));
  }

  @ParameterizedTest @MethodSource("full")
  void moveToFront_last(LinkedDeque<LinkedValue> deque) {
    checkMoveToFront(deque, deque.getLast());
  }

  private static void checkMoveToFront(LinkedDeque<LinkedValue> deque,
      @Nullable LinkedValue element) {
    assertThat(element).isNotNull();
    deque.moveToFront(element);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.peekFirst()).isEqualTo(element);
  }

  @ParameterizedTest @MethodSource("full")
  void moveToBack_first(LinkedDeque<LinkedValue> deque) {
    checkMoveToBack(deque, deque.getFirst());
  }

  @ParameterizedTest @MethodSource("full")
  void moveToBack_middle(LinkedDeque<LinkedValue> deque) {
    checkMoveToBack(deque, Iterables.get(deque, SIZE / 2));
  }

  @ParameterizedTest @MethodSource("full")
  void moveToBack_last(LinkedDeque<LinkedValue> deque) {
    checkMoveToBack(deque, deque.getLast());
  }

  private static void checkMoveToBack(LinkedDeque<LinkedValue> deque,
      @Nullable LinkedValue element) {
    assertThat(element).isNotNull();
    deque.moveToBack(element);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.getLast()).isEqualTo(element);
  }

  /* --------------- First / Last --------------- */

  @ParameterizedTest @MethodSource("empty")
  void isFirst_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.isFirst(new LinkedValue(0))).isFalse();
    assertThat(deque.isFirst(null)).isFalse();
  }

  @ParameterizedTest @MethodSource("full")
  void isFirst_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    var first = requireNonNull(deque.first);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.isFirst(first)).isTrue();
    assertThat(deque.contains(first)).isTrue();
    assertThat(deque.first).isSameInstanceAs(first);
  }

  @ParameterizedTest @MethodSource("empty")
  void isLast_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.isLast(new LinkedValue(0))).isFalse();
    assertThat(deque.isLast(null)).isFalse();
  }

  @ParameterizedTest @MethodSource("full")
  void isLast_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    var last = requireNonNull(deque.last);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.isLast(last)).isTrue();
    assertThat(deque.contains(last)).isTrue();
    assertThat(deque.last).isSameInstanceAs(last);
  }

  /* --------------- Peek --------------- */

  @ParameterizedTest @MethodSource("empty")
  @SuppressWarnings("DequePeekFirst")
  void peek_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.peek()).isNull();
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequePeekFirst")
  void peek_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    var first = requireNonNull(deque.first);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.contains(first)).isTrue();
    assertThat(deque.first).isSameInstanceAs(first);
    assertThat(deque.peek()).isSameInstanceAs(first);
  }

  @ParameterizedTest @MethodSource("empty")
  void peekFirst_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.peekFirst()).isNull();
  }

  @ParameterizedTest @MethodSource("full")
  void peekFirst_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    var first = requireNonNull(deque.first);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.contains(first)).isTrue();
    assertThat(deque.first).isSameInstanceAs(first);
    assertThat(deque.peekFirst()).isSameInstanceAs(first);
  }

  @ParameterizedTest @MethodSource("empty")
  void peekLast_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.peekLast()).isNull();
  }

  @ParameterizedTest @MethodSource("full")
  void peekLast_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    var last = requireNonNull(deque.last);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.contains(last)).isTrue();
    assertThat(deque.last).isSameInstanceAs(last);
    assertThat(deque.peekLast()).isSameInstanceAs(last);
  }

  /* --------------- Get --------------- */

  @ParameterizedTest @MethodSource("empty")
  void getFirst_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThrows(NoSuchElementException.class, deque::getFirst);
  }

  @ParameterizedTest @MethodSource("full")
  void getFirst_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    var first = requireNonNull(deque.first);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.contains(first)).isTrue();
    assertThat(deque.first).isSameInstanceAs(first);
    assertThat(deque.getFirst()).isSameInstanceAs(first);
  }

  @ParameterizedTest @MethodSource("empty")
  void getLast_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThrows(NoSuchElementException.class, deque::getLast);
  }

  @ParameterizedTest @MethodSource("full")
  void getLast_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    var last = requireNonNull(deque.last);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.contains(last)).isTrue();
    assertThat(deque.last).isSameInstanceAs(last);
    assertThat(deque.getLast()).isSameInstanceAs(last);
  }

  /* --------------- Element --------------- */

  @ParameterizedTest @MethodSource("empty")
  void element_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThrows(NoSuchElementException.class, deque::element);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeGetFirst")
  void element_whenPopulated(AbstractLinkedDeque<LinkedValue> deque) {
    var first = requireNonNull(deque.first);
    assertThat(deque).hasSize(SIZE);
    assertThat(deque.contains(first)).isTrue();
    assertThat(deque.first).isSameInstanceAs(first);
    assertThat(deque.element()).isSameInstanceAs(first);
  }

  /* --------------- Offer --------------- */

  @ParameterizedTest @MethodSource("empty")
  @SuppressWarnings("DequeOfferLast")
  void offer_whenEmpty(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(1);
    assertThat(deque.offer(value)).isTrue();
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(1);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeOfferLast")
  void offer_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(SIZE);
    assertThat(deque.offer(value)).isTrue();
    assertThat(deque.peekFirst()).isNotSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(SIZE + 1);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeOfferLast")
  void offer_whenLinked(LinkedDeque<LinkedValue> deque) {
    var value = requireNonNull(deque.peekFirst());
    assertThat(deque.offer(value)).isFalse();
    assertThat(deque).hasSize(SIZE);
  }

  @ParameterizedTest @MethodSource("empty")
  void offerFirst_whenEmpty(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(1);
    assertThat(deque.offerFirst(value)).isTrue();
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(1);
  }

  @ParameterizedTest @MethodSource("full")
  void offerFirst_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(SIZE);
    assertThat(deque.offerFirst(value)).isTrue();
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isNotSameInstanceAs(value);
    assertThat(deque).hasSize(SIZE + 1);
  }

  @ParameterizedTest @MethodSource("full")
  void offerFirst_whenLinked(LinkedDeque<LinkedValue> deque) {
    var value = requireNonNull(deque.peekFirst());
    assertThat(deque.offerFirst(value)).isFalse();
    assertThat(deque).hasSize(SIZE);
  }

  @ParameterizedTest @MethodSource("empty")
  void offerLast_whenEmpty(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(1);
    assertThat(deque.offerLast(value)).isTrue();
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(1);
  }

  @ParameterizedTest @MethodSource("full")
  void offerLast_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(SIZE);
    assertThat(deque.offerLast(value)).isTrue();
    assertThat(deque.peekFirst()).isNotSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(SIZE + 1);
  }

  @ParameterizedTest @MethodSource("full")
  void offerLast_whenLinked(LinkedDeque<LinkedValue> deque) {
    var value = requireNonNull(deque.peekFirst());
    assertThat(deque.offerLast(value)).isFalse();
    assertThat(deque).hasSize(SIZE);
  }

  /* --------------- Add --------------- */

  @ParameterizedTest @MethodSource("empty")
  void add_whenEmpty(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(1);
    assertThat(deque.add(value)).isTrue();
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(1);
  }

  @ParameterizedTest @MethodSource("full")
  void add_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(SIZE);
    assertThat(deque.add(value)).isTrue();
    assertThat(deque.peekFirst()).isNotSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(SIZE + 1);
  }

  @ParameterizedTest @MethodSource("full")
  void add_whenLinked(LinkedDeque<LinkedValue> deque) {
    var value = requireNonNull(deque.peekFirst());
    assertThat(deque.add(value)).isFalse();
  }

  @ParameterizedTest @MethodSource("empty")
  void addFirst_whenEmpty(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(1);
    deque.addFirst(value);
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(1);
  }

  @ParameterizedTest @MethodSource("full")
  void addFirst_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(SIZE);
    deque.addFirst(value);
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isNotSameInstanceAs(value);
    assertThat(deque).hasSize(SIZE + 1);
  }

  @ParameterizedTest @MethodSource("full")
  void addFirst_whenLinked(LinkedDeque<LinkedValue> deque) {
    var value = requireNonNull(deque.peekFirst());
    assertThrows(IllegalArgumentException.class, () -> deque.addFirst(value));
  }

  @ParameterizedTest @MethodSource("empty")
  void addLast_whenEmpty(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(1);
    deque.addLast(value);
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(1);
  }

  @ParameterizedTest @MethodSource("full")
  void addLast_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(SIZE);
    deque.addLast(value);
    assertThat(deque.peekFirst()).isNotSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(SIZE + 1);
  }

  @ParameterizedTest @MethodSource("full")
  void addLast_whenLinked(LinkedDeque<LinkedValue> deque) {
    var value = requireNonNull(deque.peekFirst());
    assertThrows(IllegalArgumentException.class, () -> deque.addLast(value));
  }

  @ParameterizedTest @MethodSource("empty")
  void addAll_withEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.addAll(List.of())).isFalse();
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void addAll_withPopulated(LinkedDeque<LinkedValue> deque) {
    var expected = new ArrayList<LinkedValue>();
    populate(expected);
    assertThat(deque.addAll(expected)).isTrue();
    assertThat(deque).containsExactlyElementsIn(expected).inOrder();
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings({"CollectionAddedToSelf", "ModifyingCollectionWithItself"})
  void addAll_withSelf(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.addAll(deque)).isFalse();
  }

  /* --------------- Poll --------------- */

  @ParameterizedTest @MethodSource("empty")
  @SuppressWarnings("DequePollFirst")
  void poll_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.poll()).isNull();
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequePollFirst")
  void poll_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var first = deque.peekFirst();
    assertThat(deque.poll()).isSameInstanceAs(first);
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequePollFirst")
  void poll_toEmpty(LinkedDeque<LinkedValue> deque) {
    @Var LinkedValue value;
    while ((value = deque.poll()) != null) {
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void pollFirst_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.pollFirst()).isNull();
  }

  @ParameterizedTest @MethodSource("full")
  void pollFirst_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var first = deque.peekFirst();
    assertThat(deque.pollFirst()).isSameInstanceAs(first);
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void pollFirst_toEmpty(LinkedDeque<LinkedValue> deque) {
    @Var LinkedValue value;
    while ((value = deque.pollFirst()) != null) {
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void pollLast_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.pollLast()).isNull();
  }

  @ParameterizedTest @MethodSource("full")
  void pollLast_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var last = deque.peekLast();
    assertThat(deque.pollLast()).isSameInstanceAs(last);
    assertThat(deque.contains(last)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void pollLast_toEmpty(LinkedDeque<LinkedValue> deque) {
    @Var LinkedValue value;
    while ((value = deque.pollLast()) != null) {
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  /* --------------- Remove --------------- */

  @ParameterizedTest @MethodSource("empty")
  void remove_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThrows(NoSuchElementException.class, deque::remove);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeRemoveFirst")
  void remove_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var first = deque.peekFirst();
    assertThat(deque.remove()).isSameInstanceAs(first);
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeRemoveFirst")
  void remove_toEmpty(LinkedDeque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      var value = deque.remove();
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  @SuppressWarnings("DequeRemoveFirstOccurrence")
  void removeElement_notFound(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.remove(new LinkedValue(0))).isFalse();
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeRemoveFirstOccurrence")
  void removeElement_whenFound(LinkedDeque<LinkedValue> deque) {
    var first = deque.peekFirst();
    assertThat(deque.remove(first)).isTrue();
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeRemoveFirstOccurrence")
  void removeElement_toEmpty(LinkedDeque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      var value = deque.peekFirst();
      assertThat(deque.remove(value)).isTrue();
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void removeFirst_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThrows(NoSuchElementException.class, deque::removeFirst);
  }

  @ParameterizedTest @MethodSource("full")
  void removeFirst_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var first = deque.peekFirst();
    assertThat(deque.removeFirst()).isSameInstanceAs(first);
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void removeFirst_toEmpty(LinkedDeque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      var value = deque.removeFirst();
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void removeLast_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThrows(NoSuchElementException.class, deque::removeLast);
  }

  @ParameterizedTest @MethodSource("full")
  void removeLast_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var last = deque.peekLast();
    assertThat(deque.removeLast()).isSameInstanceAs(last);
    assertThat(deque.contains(last)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void removeLast_toEmpty(LinkedDeque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      var value = deque.removeLast();
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void removeFirstOccurrence_notFound(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.removeFirstOccurrence(new LinkedValue(0))).isFalse();
  }

  @ParameterizedTest @MethodSource("full")
  void removeFirstOccurrence_whenFound(LinkedDeque<LinkedValue> deque) {
    var first = deque.peekFirst();
    assertThat(deque.removeFirstOccurrence(first)).isTrue();
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void removeFirstOccurrence_toEmpty(LinkedDeque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      var value = deque.peekFirst();
      assertThat(deque.removeFirstOccurrence(value)).isTrue();
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void removeLastOccurrence_notFound(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.removeLastOccurrence(new LinkedValue(0))).isFalse();
  }

  @ParameterizedTest @MethodSource("full")
  void removeLastOccurrence_whenFound(LinkedDeque<LinkedValue> deque) {
    var first = deque.peekFirst();
    assertThat(deque.removeLastOccurrence(first)).isTrue();
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void removeLastOccurrence_toEmpty(LinkedDeque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      var value = deque.peekFirst();
      assertThat(deque.removeLastOccurrence(value)).isTrue();
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("empty")
  void removeAll_withEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.removeAll(List.of())).isFalse();
    assertThat(deque).isExhaustivelyEmpty();
  }

  @ParameterizedTest @MethodSource("full")
  void remove_withPopulated(LinkedDeque<LinkedValue> deque) {
    var first = requireNonNull(deque.peekFirst());
    assertThat(deque.removeAll(List.of(first))).isTrue();
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void removeAll_toEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.removeAll(List.copyOf(deque))).isTrue();
    assertThat(deque).isExhaustivelyEmpty();
  }

  /* --------------- Stack --------------- */

  @ParameterizedTest @MethodSource("empty")
  @SuppressWarnings("DequeAddFirst")
  void push_whenEmpty(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(1);
    deque.push(value);
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isSameInstanceAs(value);
    assertThat(deque).hasSize(1);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeAddFirst")
  void push_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var value = new LinkedValue(SIZE);
    deque.push(value);
    assertThat(deque.peekFirst()).isSameInstanceAs(value);
    assertThat(deque.peekLast()).isNotSameInstanceAs(value);
    assertThat(deque).hasSize(SIZE + 1);
  }

  @ParameterizedTest @MethodSource("full")
  void push_whenLinked(LinkedDeque<LinkedValue> deque) {
    var value = requireNonNull(deque.peekFirst());
    assertThrows(IllegalArgumentException.class, () -> deque.push(value));
  }

  @ParameterizedTest @MethodSource("empty")
  void pop_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThrows(NoSuchElementException.class, deque::pop);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeRemoveFirst")
  void pop_whenPopulated(LinkedDeque<LinkedValue> deque) {
    var first = deque.peekFirst();
    assertThat(deque.pop()).isSameInstanceAs(first);
    assertThat(deque.contains(first)).isFalse();
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("DequeRemoveFirst")
  void pop_toEmpty(LinkedDeque<LinkedValue> deque) {
    while (!deque.isEmpty()) {
      var value = deque.pop();
      assertThat(deque.contains(value)).isFalse();
    }
    assertThat(deque).isExhaustivelyEmpty();
  }

  /* --------------- Iterators --------------- */

  @ParameterizedTest @MethodSource("empty")
  void iterator_noMoreElements(LinkedDeque<LinkedValue> deque) {
    var iterator = deque.iterator();
    assertThrows(NoSuchElementException.class, iterator::next);
  }

  @ParameterizedTest @MethodSource("empty")
  void iterator_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.iterator().hasNext()).isFalse();
    assertThat(deque.iterator().peek()).isNull();
  }

  @ParameterizedTest @MethodSource("full")
  void iterator_whenWarmed(LinkedDeque<LinkedValue> deque) {
    var expected = new ArrayList<LinkedValue>();
    populate(expected);

    assertThat(deque.peekFirst()).isNotNull();
    assertThat(Iterators.size(deque.iterator())).isEqualTo(deque.size());
    assertThat(elementsEqual(deque.iterator(), expected.iterator())).isTrue();
  }

  @ParameterizedTest @MethodSource("full")
  void iterator_removal(LinkedDeque<LinkedValue> deque) {
    var iterator = deque.iterator();
    var value = iterator.next();
    iterator.remove();

    @Var int remaining = 0;
    while (iterator.hasNext()) {
      assertThat(iterator.next()).isNotSameInstanceAs(value);
      remaining++;
    }
    assertThat(remaining).isEqualTo(SIZE - 1);
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void iterator_removal_exception(LinkedDeque<LinkedValue> deque) {
    var iterator = deque.iterator();
    iterator.next();
    iterator.remove();
    assertThrows(IllegalStateException.class, iterator::remove);
  }

  @ParameterizedTest @MethodSource("empty")
  void descendingIterator_noMoreElements(LinkedDeque<LinkedValue> deque) {
    var iterator = deque.descendingIterator();
    assertThrows(NoSuchElementException.class, iterator::next);
  }

  @ParameterizedTest @MethodSource("empty")
  void descendingIterator_whenEmpty(LinkedDeque<LinkedValue> deque) {
    assertThat(deque.descendingIterator().hasNext()).isFalse();
    assertThat(deque.descendingIterator().peek()).isNull();
  }

  @ParameterizedTest @MethodSource("full")
  void descendingIterator_whenWarmed(LinkedDeque<LinkedValue> deque) {
    var expected = new ArrayList<LinkedValue>();
    populate(expected);
    Collections.reverse(expected);

    assertThat(deque.descendingIterator().peek()).isNotNull();
    assertThat(elementsEqual(deque.descendingIterator(), expected.iterator())).isTrue();
  }

  @ParameterizedTest @MethodSource("full")
  void descendingIterator_removal(LinkedDeque<LinkedValue> deque) {
    var iterator = deque.descendingIterator();
    var value = iterator.next();
    iterator.remove();

    @Var int remaining = 0;
    while (iterator.hasNext()) {
      assertThat(iterator.next()).isNotEqualTo(value);
      remaining++;
    }
    assertThat(remaining).isEqualTo(SIZE - 1);
    assertThat(deque).hasSize(SIZE - 1);
  }

  @ParameterizedTest @MethodSource("full")
  void concat(LinkedDeque<LinkedValue> deque) {
    var expect = ImmutableList.copyOf(
        Iterators.concat(deque.iterator(), deque.descendingIterator()));
    Iterable<LinkedValue> actual = () -> PeekingIterator.concat(
        deque.iterator(), deque.descendingIterator());
    assertThat(actual).containsExactlyElementsIn(expect).inOrder();
  }

  @ParameterizedTest @MethodSource("full")
  void concat_peek(LinkedDeque<LinkedValue> deque) {
    var iterator = PeekingIterator.concat(deque.iterator(), deque.iterator());
    while (iterator.hasNext()) {
      var expected = iterator.peek();
      assertThat(iterator.next()).isEqualTo(expected);
    }
    assertThat(iterator.peek()).isNull();
  }

  @ParameterizedTest @MethodSource("empty")
  void concat_noMoreElements(LinkedDeque<LinkedValue> deque) {
    var iterator = PeekingIterator.concat(deque.iterator(), deque.iterator());
    assertThrows(NoSuchElementException.class, iterator::next);
  }

  @ParameterizedTest @MethodSource("full")
  @SuppressWarnings("SequencedCollectionGetFirst")
  void comparing(LinkedDeque<LinkedValue> deque) {
    var expect = ImmutableList.copyOf(
        Iterators.concat(deque.iterator(), deque.descendingIterator()));
    var actual = PeekingIterator.comparing(
        deque.iterator(), deque.descendingIterator(), comparator().reversed());
    assertThat(actual.peek()).isEqualTo(expect.get(0));
    assertThat(ImmutableList.copyOf(actual)).containsExactlyElementsIn(expect).inOrder();
  }

  @ParameterizedTest @MethodSource("full")
  void comparing_peek(LinkedDeque<LinkedValue> deque) {
    var ascending = PeekingIterator.comparing(
        deque.descendingIterator(), deque.iterator(), comparator().reversed());
    var descending = PeekingIterator.comparing(
        deque.descendingIterator(), deque.iterator(), comparator());
    assertThat(ascending.peek()).isEqualTo(deque.peekFirst());
    assertThat(descending.peek()).isEqualTo(deque.peekLast());
  }

  @ParameterizedTest @MethodSource("full")
  void comparing_uneven(LinkedDeque<LinkedValue> deque) {
    var empty = new AccessOrderDeque<LinkedValue>().iterator();
    var left = PeekingIterator.comparing(deque.iterator(), empty, comparator().reversed());
    var right = PeekingIterator.comparing(empty, deque.iterator(), comparator().reversed());

    assertThat(left.peek()).isEqualTo(deque.getFirst());
    assertThat(right.peek()).isEqualTo(deque.getFirst());
  }

  private static Comparator<LinkedValue> comparator() {
    return Comparator.comparingInt((LinkedValue v) -> v.value);
  }

  /* --------------- Deque providers --------------- */

  static Stream<LinkedDeque<LinkedValue>> empty() {
    return Stream.of(new AccessOrderDeque<LinkedValue>(), new WriteOrderDeque<LinkedValue>());
  }

  static Stream<LinkedDeque<LinkedValue>> full() {
    var accessOrder = new AccessOrderDeque<LinkedValue>();
    var writeOrder = new WriteOrderDeque<LinkedValue>();
    populate(accessOrder);
    populate(writeOrder);
    return Stream.of(accessOrder, writeOrder);
  }

  private static void populate(Collection<LinkedValue> collection) {
    for (int i = 0; i < SIZE; i++) {
      collection.add(new LinkedValue(i));
    }
  }

  private static final class LinkedValue
      implements AccessOrder<LinkedValue>, WriteOrder<LinkedValue> {
    private final int value;

    private @Nullable LinkedValue prev;
    private @Nullable LinkedValue next;

    LinkedValue(int value) {
      this.value = value;
    }
    @Override public @Nullable LinkedValue getPreviousInAccessOrder() {
      return prev;
    }
    @Override public void setPreviousInAccessOrder(@Nullable LinkedValue prev) {
      this.prev = prev;
    }
    @Override public @Nullable LinkedValue getNextInAccessOrder() {
      return next;
    }
    @Override public void setNextInAccessOrder(@Nullable LinkedValue next) {
      this.next = next;
    }
    @Override public @Nullable LinkedValue getPreviousInWriteOrder() {
      return prev;
    }
    @Override public void setPreviousInWriteOrder(@Nullable LinkedValue prev) {
      this.prev = prev;
    }
    @Override public @Nullable LinkedValue getNextInWriteOrder() {
      return next;
    }
    @Override public void setNextInWriteOrder(@Nullable LinkedValue next) {
      this.next = next;
    }
    @Override public boolean equals(Object o) {
      return (o instanceof LinkedValue) && (value == ((LinkedValue) o).value);
    }
    @Override public int hashCode() {
      return value;
    }
    @Override public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("value", value)
          .add("prev", (prev == null) ? null : prev.value)
          .add("next", (next == null) ? null : next.value)
          .toString();
    }
  }
}
