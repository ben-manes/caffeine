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
package com.github.benmanes.caffeine.testing;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

/**
 * A matcher that performs an exhaustive empty check throughout the {@link Iterable},
 * {@link Collection}, {@link Set}, {@link List}, {@link Queue}, and {@link Deque} contracts.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsEmptyIterable<E> extends TypeSafeDiagnosingMatcher<Iterable<? extends E>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("empty");
  }

  @Override
  protected boolean matchesSafely(Iterable<? extends E> iterable, Description description) {
    DescriptionBuilder desc = new DescriptionBuilder(description);

    checkIterable(iterable, desc);
    if (iterable instanceof Collection<?>) {
      checkCollection((Collection<? extends E>) iterable, desc);
    }
    if (iterable instanceof Set<?>) {
      checkSet((Set<? extends E>) iterable, desc);
    }
    if (iterable instanceof List<?>) {
      checkList((List<? extends E>) iterable, desc);
    }
    if (iterable instanceof Queue<?>) {
      checkQueue((Queue<? extends E>) iterable, desc);
    }
    if (iterable instanceof Deque<?>) {
      checkDeque((Deque<? extends E>) iterable, desc);
    }
    return desc.matches();
  }

  private void checkIterable(Iterable<? extends E> i, DescriptionBuilder builder) {
    builder.expectThat("iterator has data", i.iterator().hasNext(), is(false));
  }

  private void checkCollection(Collection<? extends E> c, DescriptionBuilder builder) {
    builder.expectThat("empty collection", c, hasSize(0));
    builder.expectThat("not empty", c.isEmpty(), is(true));
    builder.expectThat("toArray has data", c.toArray(), is(arrayWithSize(0)));
    builder.expectThat("toArray has data", c.toArray(new Object[0]), is(arrayWithSize(0)));
  }

  @SuppressWarnings("unchecked")
  private void checkSet(Set<? extends E> set, DescriptionBuilder builder) {
    builder.expectThat("hashcode", set.hashCode(), is(equalTo(emptySet().hashCode())));
    builder.expectThat("collection not equal to empty set", (Set<Object>) set, is(emptySet()));
    builder.expectThat("empty set not equal to collection", emptySet(), is((Set<Object>) set));
  }

  @SuppressWarnings("unchecked")
  private void checkList(List<? extends E> list, DescriptionBuilder builder) {
    builder.expectThat("hashcode", list.hashCode(), is(equalTo(emptyList().hashCode())));
    builder.expectThat("collection not equal to empty list", (List<Object>) list, is(emptyList()));
    builder.expectThat("empty list not equal to collection", emptyList(), is((List<Object>) list));
  }

  private void checkQueue(Queue<? extends E> queue, DescriptionBuilder builder) {
    builder.expectThat("empty queue", queue.peek(), is(nullValue()));
    checkNoSuchElementException("remove", builder, queue::remove);
    checkNoSuchElementException("element", builder, queue::element);
  }

  private void checkDeque(Deque<? extends E> deque, DescriptionBuilder builder) {
    builder.expectThat("empty deque", deque.peekFirst(), is(nullValue()));
    builder.expectThat("empty deque", deque.peekLast(), is(nullValue()));
    builder.expectThat("empty deque", deque.descendingIterator().hasNext(), is(false));
  }

  private void checkNoSuchElementException(String label,
      DescriptionBuilder builder, Runnable method) {
    try {
      method.run();
      builder.expected("element");
    } catch (NoSuchElementException e) {}
  }

  public static <E> IsEmptyIterable<E> deeplyEmpty() {
    return new IsEmptyIterable<>();
  }
}
