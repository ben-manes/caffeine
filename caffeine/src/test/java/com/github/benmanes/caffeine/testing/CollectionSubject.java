/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertAbout;

import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;

import com.google.common.truth.FailureMetadata;

/**
 * Additional propositions for {@link Collection} subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class CollectionSubject extends com.google.common.truth.IterableSubject {
  private final Collection<?> actual;

  public CollectionSubject(FailureMetadata metadata, Collection<?> subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  public static Factory<CollectionSubject, Collection<?>> collection() {
    return CollectionSubject::new;
  }

  public static <E> CollectionSubject assertThat(Collection<E> actual) {
    return assertAbout(collection()).that(actual);
  }

  /** Fails if the collection does not have the given size. */
  public final void hasSize(long expectedSize) {
    hasSize(Math.toIntExact(expectedSize));
  }

  /** Fails if the collection does not have less than the given size. */
  public void hasSizeLessThan(long other) {
    checkArgument(other >= 0, "expectedSize (%s) must be >= 0", other);
    check("size()").that(actual.size()).isLessThan(Math.toIntExact(other));
  }

  /**
   * Fails if the subject is not empty. This differs from {@link #isEmpty()} by checking throughout
   * the {@link Iterable}, {@link Collection}, {@link Set}, {@link List}, {@link Queue}, and
   * {@link Deque} contracts.
   */
  public void isExhaustivelyEmpty() {
    checkIterable();
    checkCollection();
    if (actual instanceof Set<?>) {
      checkSet((Set<?>) actual);
    }
    if (actual instanceof List<?>) {
      checkList((List<?>) actual);
    }
    if (actual instanceof Queue<?>) {
      checkQueue((Queue<?>) actual);
    }
    if (actual instanceof Deque<?>) {
      checkDeque((Deque<?>) actual);
    }
  }

  private void checkIterable() {
    check("iterator().hasNext()").that(actual.iterator().hasNext()).isFalse();
  }

  @SuppressWarnings("CollectionToArray")
  private void checkCollection() {
    check("size()").that(actual).hasSize(0);
    check("isEmpty()").that(actual).isEmpty();
    check("toArray()").that(actual.toArray()).isEmpty();
    check("toArray(E[])").that(actual.toArray(new Object[0])).isEmpty();
    check("toArray(IntFunction)").that(actual.toArray(Object[]::new)).isEmpty();
  }

  private void checkSet(Set<?> set) {
    check("actual.equals(empty)").that(set).isEqualTo(Set.of());
    check("empty.equals(actual)").that(Set.of()).isEqualTo(set);
    check("hashCode()").that(set.hashCode()).isEqualTo(Set.of().hashCode());
  }

  private void checkList(List<?> list) {
    check("actual.equals(empty)").that(list).isEqualTo(List.of());
    check("empty.equals(actual)").that(List.of()).isEqualTo(list);
    check("hashCode()").that(list.hashCode()).isEqualTo(List.of().hashCode());
  }

  private void checkQueue(Queue<?> queue) {
    check("peek()").that(queue.peek()).isNull();
    try {
      failWithActual("remove()", queue.remove());
    } catch (NoSuchElementException expected) { /* ignored */ }
    try {
      failWithActual("element()", queue.element());
    } catch (NoSuchElementException expected) { /* ignored */ }
  }

  private void checkDeque(Deque<?> deque) {
    check("peekFirst()").that(deque.peekFirst()).isNull();
    check("peekLast()").that(deque.peekLast()).isNull();
    check("descendingIterator().hasNext()").that(deque.descendingIterator().hasNext()).isFalse();
  }
}
