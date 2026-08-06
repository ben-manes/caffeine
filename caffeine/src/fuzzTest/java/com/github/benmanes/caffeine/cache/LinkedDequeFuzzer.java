/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.benmanes.caffeine.cache.AccessOrderDeque.AccessOrder;
import com.github.benmanes.caffeine.cache.WriteOrderDeque.WriteOrder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.Var;

/**
 * A fuzzer that exercises {@link AbstractLinkedDeque} by generating random sequences of link,
 * unlink, and reorder operations. The ordering is validated against a reference list and the
 * structural invariants are checked after every operation: the links agree in both directions, the
 * chain reaches the tail without a cycle, and the first and last pointers hold the documented
 * {@code (first == null && last == null) || (first.prev == null)} relationship.
 * <p>
 * The deque backs the access-order and write-order queues, so a broken link is a cache-wide
 * corruption. Operation sequences are what expose it, as the unit tests and the mutation coverage
 * both exercise the methods individually rather than in combination.
 */
@SuppressWarnings({"DequeRemoveFirstOccurrence", "ListAddFirst", "ListRemoveFirst",
    "ListRemoveLast", "SequencedCollectionGetFirst", "SequencedCollectionGetLast"})
final class LinkedDequeFuzzer {
  private static final Operation[] OPERATIONS = Operation.values();

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  @FuzzTest(maxDuration = "5m")
  void deque(FuzzedDataProvider data) {
    var deque = data.consumeBoolean() ? new AccessOrderDeque<LinkedValue>()
        : new WriteOrderDeque<LinkedValue>();
    var model = new ArrayList<LinkedValue>();
    @Var int nextValue = 0;

    int operations = data.consumeInt(0, 200);
    for (int i = 0; i < operations; i++) {
      var operation = OPERATIONS[data.consumeInt(0, OPERATIONS.length - 1)];
      nextValue = execute(operation, data, deque, model, nextValue);
      validate(deque, model);
    }
  }

  /** Applies the operation to both the deque and the reference model. */
  private static int execute(Operation operation, FuzzedDataProvider data,
      LinkedDeque<LinkedValue> deque, List<LinkedValue> model, int lastValue) {
    @Var int nextValue = lastValue;
    switch (operation) {
      case ADD_FIRST: {
        var value = new LinkedValue(nextValue++);
        deque.addFirst(value);
        model.add(0, value);
        break;
      }
      case ADD_LAST: {
        var value = new LinkedValue(nextValue++);
        deque.addLast(value);
        model.add(value);
        break;
      }
      case POLL_FIRST: {
        assertThat(deque.pollFirst()).isSameInstanceAs(model.isEmpty() ? null : model.get(0));
        if (!model.isEmpty()) {
          model.remove(0);
        }
        break;
      }
      case POLL_LAST: {
        assertThat(deque.pollLast())
            .isSameInstanceAs(model.isEmpty() ? null : model.get(model.size() - 1));
        if (!model.isEmpty()) {
          model.remove(model.size() - 1);
        }
        break;
      }
      case REMOVE: {
        if (!model.isEmpty()) {
          var value = model.remove(index(data, model));
          assertThat(deque.remove(value)).isTrue();
        }
        break;
      }
      case MOVE_TO_FRONT: {
        if (!model.isEmpty()) {
          var value = model.remove(index(data, model));
          model.add(0, value);
          deque.moveToFront(value);
        }
        break;
      }
      case MOVE_TO_BACK: {
        if (!model.isEmpty()) {
          var value = model.remove(index(data, model));
          model.add(value);
          deque.moveToBack(value);
        }
        break;
      }
      case ITERATOR_REMOVE: {
        if (!model.isEmpty()) {
          int position = index(data, model);
          var iterator = deque.iterator();
          for (int i = 0; i <= position; i++) {
            assertThat(iterator.next()).isSameInstanceAs(model.get(i));
          }
          iterator.remove();
          model.remove(position);
        }
        break;
      }
      case CLEAR: {
        deque.clear();
        model.clear();
        break;
      }
    }
    return nextValue;
  }

  /** Returns a position within the model. */
  private static int index(FuzzedDataProvider data, Collection<LinkedValue> model) {
    return data.consumeInt(0, model.size() - 1);
  }

  /** Asserts that the deque agrees with the model and that its links are well formed. */
  private static void validate(LinkedDeque<LinkedValue> deque, List<LinkedValue> model) {
    assertThat(deque.size()).isEqualTo(model.size());
    assertThat(deque.isEmpty()).isEqualTo(model.isEmpty());
    assertThat(ImmutableList.copyOf(deque)).containsExactlyElementsIn(model).inOrder();
    assertThat(ImmutableList.copyOf(deque.descendingIterator()))
        .containsExactlyElementsIn(Lists.reverse(model)).inOrder();

    if (model.isEmpty()) {
      assertThat(deque.peekFirst()).isNull();
      assertThat(deque.peekLast()).isNull();
      return;
    }

    // The documented invariant: a non-empty deque is anchored at both ends
    var head = requireNonNull(deque.peekFirst());
    var tail = requireNonNull(deque.peekLast());
    assertThat(deque.getPrevious(head)).isNull();
    assertThat(deque.getNext(tail)).isNull();

    // Walking forward must reach the tail through consistent links, without revisiting a node
    @Var
    LinkedValue prior = null;
    @Var
    int count = 0;
    for (@Var
    var e = head; e != null; e = deque.getNext(e)) {
      assertThat(deque.getPrevious(e)).isSameInstanceAs(prior);
      prior = e;
      count++;
      assertWithMessage("cycle detected after %s links", count).that(count).isAtMost(model.size());
    }
    assertThat(count).isEqualTo(model.size());
    assertThat(prior).isSameInstanceAs(tail);
  }

  enum Operation {
    ADD_FIRST, ADD_LAST, POLL_FIRST, POLL_LAST, REMOVE,
    MOVE_TO_FRONT, MOVE_TO_BACK, ITERATOR_REMOVE, CLEAR
  }

  /** An element that may be linked into either deque, as the test suite's own value is. */
  static final class LinkedValue implements AccessOrder<LinkedValue>, WriteOrder<LinkedValue> {
    final int value;

    @Nullable
    LinkedValue prev;
    @Nullable
    LinkedValue next;

    LinkedValue(int value) {
      this.value = value;
    }

    @Override
    public @Nullable LinkedValue getPreviousInAccessOrder() {
      return prev;
    }

    @Override
    public void setPreviousInAccessOrder(@Nullable LinkedValue prev) {
      this.prev = prev;
    }

    @Override
    public @Nullable LinkedValue getNextInAccessOrder() {
      return next;
    }

    @Override
    public void setNextInAccessOrder(@Nullable LinkedValue next) {
      this.next = next;
    }

    @Override
    public @Nullable LinkedValue getPreviousInWriteOrder() {
      return prev;
    }

    @Override
    public void setPreviousInWriteOrder(@Nullable LinkedValue prev) {
      this.prev = prev;
    }

    @Override
    public @Nullable LinkedValue getNextInWriteOrder() {
      return next;
    }

    @Override
    public void setNextInWriteOrder(@Nullable LinkedValue next) {
      this.next = next;
    }

    @Override
    public String toString() {
      return "LinkedValue[" + value + "]";
    }
  }
}
