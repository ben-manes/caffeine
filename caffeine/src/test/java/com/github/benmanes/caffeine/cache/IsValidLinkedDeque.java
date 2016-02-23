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

import static com.github.benmanes.caffeine.testing.IsEmptyIterable.deeplyEmpty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Iterator;
import java.util.Set;
import java.util.function.Supplier;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.testing.DescriptionBuilder;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link LinkedDeque} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidLinkedDeque<E> extends TypeSafeDiagnosingMatcher<LinkedDeque<E>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("valid");
  }

  @Override
  protected boolean matchesSafely(LinkedDeque<E> deque, Description description) {
    DescriptionBuilder desc = new DescriptionBuilder(description);

    if (deque.isEmpty()) {
      checkEmpty(deque, desc);
    }
    checkIterator(deque, deque.iterator(), desc);
    checkIterator(deque, deque.descendingIterator(), desc);

    return desc.matches();
  }

  void checkEmpty(LinkedDeque<? extends E> deque, DescriptionBuilder desc) {
    desc.expectThat("empty deque", deque, deeplyEmpty());
    desc.expectThat("empty deque", deque.pollFirst(), is(nullValue()));
    desc.expectThat("empty deque", deque.pollLast(), is(nullValue()));
    desc.expectThat("empty deque", deque.poll(), is(nullValue()));
  }

  void checkIterator(LinkedDeque<E> deque, Iterator<E> iterator, DescriptionBuilder desc) {
    Set<E> seen = Sets.newIdentityHashSet();
    while (iterator.hasNext()) {
      E element = iterator.next();
      checkElement(deque, element, desc);
      Supplier<String> errorMsg = () -> String.format("Loop detected: %s in %s", element, seen);
      desc.expectThat(errorMsg, seen.add(element), is(true));
    }
    desc.expectThat("deque size", deque, hasSize(seen.size()));
  }

  void checkElement(LinkedDeque<E> deque, E element, DescriptionBuilder desc) {
    E first = deque.peekFirst();
    E last = deque.peekLast();
    if (element == first) {
      desc.expectThat("not null prev", deque.getPrevious(element), is(nullValue()));
    }
    if (element == last) {
      desc.expectThat("not null next", deque.getNext(element), is(nullValue()));
    }
    if ((element != first) && (element != last)) {
      desc.expectThat("empty deque", deque.getPrevious(element), is(not(nullValue())));
      desc.expectThat("empty deque", deque.getNext(element), is(not(nullValue())));
    }
  }

  public static <E> IsValidLinkedDeque<E> validLinkedDeque() {
    return new IsValidLinkedDeque<>();
  }
}
