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
package com.github.benmanes.caffeine.matchers;

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
import java.util.Queue;
import java.util.Set;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

/**
 * A matcher that performs an exhaustive empty check throughout the {@link Collection}, {@link Set},
 * {@link List}, {@link Queue}, and {@link Deque} contracts.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsEmptyCollection<E> extends TypeSafeDiagnosingMatcher<Collection<? extends E>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("empty");
  }

  @Override
  protected boolean matchesSafely(Collection<? extends E> c, Description description) {
    DescriptionBuilder builder = new DescriptionBuilder(description);

    checkCollection(c, builder);
    if (c instanceof Set<?>) {
      checkSet((Set<? extends E>) c, builder);
    }
    if (c instanceof List<?>) {
      checkList((List<? extends E>) c, builder);
    }
    if (c instanceof Queue<?>) {
      checkQueue((Queue<? extends E>) c, builder);
    }
    if (c instanceof Deque<?>) {
      checkDeque((Deque<? extends E>) c, builder);
    }
    return builder.matches();
  }

  private void checkCollection(Collection<? extends E> c, DescriptionBuilder builder) {
    builder.expectThat(c, hasSize(0));
    builder.expectThat("not empty", c.isEmpty(), is(true));
    builder.expectThat("iterator has data", c.iterator().hasNext(), is(false));
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
    builder.expectThat(queue.peek(), is(nullValue()));
  }

  private void checkDeque(Deque<? extends E> deque, DescriptionBuilder builder) {
    builder.expectThat(deque.peekFirst(), is(nullValue()));
    builder.expectThat(deque.peekLast(), is(nullValue()));
    builder.expectThat(deque.iterator().hasNext(), is(false));
    builder.expectThat(deque.descendingIterator().hasNext(), is(false));
  }

  @Factory
  public static <E> IsEmptyCollection<E> emptyCollection() {
    return new IsEmptyCollection<E>();
  }
}