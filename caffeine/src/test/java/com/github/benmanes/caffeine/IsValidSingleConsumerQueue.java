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

import static com.github.benmanes.caffeine.testing.IsEmptyIterable.deeplyEmpty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.testing.DescriptionBuilder;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link SingleConsumerQueue} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("deprecation")
public final class IsValidSingleConsumerQueue<E>
    extends TypeSafeDiagnosingMatcher<SingleConsumerQueue<E>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("singleConsumerQueue");
  }

  @Override
  protected boolean matchesSafely(SingleConsumerQueue<E> queue, Description description) {
    DescriptionBuilder builder = new DescriptionBuilder(description);

    if (queue.isEmpty()) {
      builder.expectThat("empty queue", queue, is(deeplyEmpty()));
      builder.expectThat("empty queue", queue.head, is(queue.tail));
      builder.expectThat("empty queue", queue.head.next, is(nullValue()));
    }
    builder.expectThat("corrupted queue node", queue.tail.next, is(nullValue()));
    checkForLoop(queue, builder);
    checkArena(queue, builder);

    return builder.matches();
  }

  void checkForLoop(SingleConsumerQueue<E> queue, DescriptionBuilder builder) {
    builder.expectThat("Expected sentinel node", queue.head.value, is(nullValue()));
    Set<SingleConsumerQueue.Node<E>> seen = Sets.newIdentityHashSet();
    SingleConsumerQueue.Node<E> node = queue.head.next;
    while (node != null) {
      SingleConsumerQueue.Node<E> current = node;
      Supplier<String> errorMsg = () -> String.format("Loop detected: %s in %s", current, seen);
      builder.expectThat(errorMsg, seen.add(node), is(true));
      builder.expectThat("not tail", node, is(not(queue.head)));
      builder.expectThat("not completed", node.isDone(), is(true));
      builder.expectThat("not null value", node.value, is(not(nullValue())));
      node = node.next;
    }
    builder.expectThat("queue size", queue, hasSize(seen.size()));
  }

  void checkArena(SingleConsumerQueue<E> queue, DescriptionBuilder builder) {
    for (AtomicReference<?> slot : queue.arena) {
      builder.expectThat("not null arena slot", slot.get(), is(nullValue()));
    }
  }

  public static <E> IsValidSingleConsumerQueue<E> validate() {
    return new IsValidSingleConsumerQueue<E>();
  }
}
