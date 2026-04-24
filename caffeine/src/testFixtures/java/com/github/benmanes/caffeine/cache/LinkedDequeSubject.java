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

import static com.google.common.truth.Truth.assertAbout;
import static java.util.Objects.requireNonNull;

import java.util.Iterator;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.testing.CollectionSubject;
import com.google.common.collect.Sets;
import com.google.common.truth.FailureMetadata;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Propositions for {@link LinkedDeque} subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class LinkedDequeSubject extends CollectionSubject {
  private final LinkedDeque<Object> actual;

  @SuppressWarnings("unchecked")
  private LinkedDequeSubject(FailureMetadata metadata, @Nullable LinkedDeque<?> subject) {
    super(metadata, subject);
    this.actual = requireNonNull((LinkedDeque<Object>) subject);
  }

  public static Factory<LinkedDequeSubject, LinkedDeque<?>> deque() {
    return LinkedDequeSubject::new;
  }

  public static LinkedDequeSubject assertThat(LinkedDeque<?> actual) {
    return assertAbout(deque()).that(actual);
  }

  public void isValid() {
    if (actual.isEmpty()) {
      isExhaustivelyEmpty();
    }
    var forward = checkIterator(actual.iterator());
    var descending = checkIterator(actual.descendingIterator());
    check("forward vs descending elements").that(forward).containsExactlyElementsIn(descending);
    if (!actual.isEmpty()) {
      check("peekFirst()").that(actual.peekFirst()).isSameInstanceAs(actual.getFirst());
      check("peekLast()").that(actual.peekLast()).isSameInstanceAs(actual.getLast());
    }
  }

  @CanIgnoreReturnValue
  private Set<Object> checkIterator(Iterator<?> iterator) {
    Set<Object> seen = Sets.newIdentityHashSet();
    while (iterator.hasNext()) {
      var element = iterator.next();
      checkElement(element);
      check("loop").withMessage("Loop detected: %s in %s", element, seen)
          .that(seen.add(element)).isTrue();
    }
    hasSize(seen.size());
    return seen;
  }

  private void checkElement(Object element) {
    var first = actual.peekFirst();
    var last = actual.peekLast();
    var prev = actual.getPrevious(element);
    var next = actual.getNext(element);
    if (element == first) {
      check("getPrevious(e)").that(prev).isNull();
    } else {
      check("getPrevious(e)").that(prev).isNotNull();
      check("prev.next").that(actual.getNext(requireNonNull(prev))).isSameInstanceAs(element);
    }
    if (element == last) {
      check("getNext(e)").that(next).isNull();
    } else {
      check("getNext(e)").that(next).isNotNull();
      check("next.prev").that(actual.getPrevious(requireNonNull(next))).isSameInstanceAs(element);
    }
  }
}
