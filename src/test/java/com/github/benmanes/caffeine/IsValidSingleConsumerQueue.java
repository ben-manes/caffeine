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

import static com.github.benmanes.caffeine.matchers.IsEmptyIterable.deeplyEmpty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Set;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.matchers.DescriptionBuilder;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link SingleConsumerQueue} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidSingleConsumerQueue<E>
    extends TypeSafeDiagnosingMatcher<SingleConsumerQueue<? extends E>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("singleConsumerQueue");
  }

  @Override
  protected boolean matchesSafely(SingleConsumerQueue<? extends E> queue, Description description) {
    DescriptionBuilder builder = new DescriptionBuilder(description);

    if (queue.isEmpty()) {
      builder.expectThat(queue, is(deeplyEmpty()));
      builder.expectThat(queue.tail, is(queue.head));
      builder.expectThat(queue.tail.next, is(nullValue()));
    }
    builder.expectThat(queue.head.next, is(nullValue()));
    checkForLoop(queue, builder);

    return builder.matches();
  }

  void checkForLoop(SingleConsumerQueue<? extends E> queue, DescriptionBuilder builder) {
    Set<SingleConsumerQueue.Node<? extends E>> seen = Sets.newIdentityHashSet();
    SingleConsumerQueue.Node<? extends E> node = queue.tail;
    while (node.next != null) {
      String errorMsg = String.format("Loop detected: %s in %s", node, seen);
      builder.expectThat(errorMsg, seen.add(node), is(true));
      if (node != queue.tail) {
        builder.expectThat(node.value, is(not(nullValue())));
      }
      node = node.next;
    }
    builder.expectThat(queue, hasSize(seen.size()));
  }

  @Factory
  public static <E> IsValidSingleConsumerQueue<E> validate() {
    return new IsValidSingleConsumerQueue<E>();
  }
}
