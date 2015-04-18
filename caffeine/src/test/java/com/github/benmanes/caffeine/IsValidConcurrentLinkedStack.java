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
import static org.hamcrest.Matchers.nullValue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.ConcurrentLinkedStack.Node;
import com.github.benmanes.caffeine.matchers.DescriptionBuilder;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link ConcurrentLinkedStack} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidConcurrentLinkedStack<E>
    extends TypeSafeDiagnosingMatcher<ConcurrentLinkedStack<E>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("singleConsumerQueue");
  }

  @Override
  protected boolean matchesSafely(ConcurrentLinkedStack<E> stack, Description description) {
    DescriptionBuilder builder = new DescriptionBuilder(description);

    if (stack.isEmpty()) {
      builder.expectThat("empty stack", stack, is(deeplyEmpty()));
      builder.expectThat("empty stack", stack.top, is(nullValue()));
      builder.expectThat("empty stack", stack.asLifoQueue(), is(deeplyEmpty()));
    }
    checkForLoop(stack, builder);
    checkArena(stack, builder);

    return builder.matches();
  }

  void checkForLoop(ConcurrentLinkedStack<E> stack, DescriptionBuilder builder) {
    Set<Node<E>> seen = Sets.newIdentityHashSet();
    Node<E> node = stack.top;
    while (node != null) {
      if (node.get() != null) {
        Node<E> current = node;
        Supplier<String> errorMsg = () -> String.format("Loop detected: %s in %s", current, seen);
        builder.expectThat(errorMsg, seen.add(node), is(true));
        builder.expectThat("not completed", node.isDone(), is(true));
      }
      node = node.next;
    }
    builder.expectThat("stack size", stack, hasSize(seen.size()));
  }

  void checkArena(ConcurrentLinkedStack<E> stack, DescriptionBuilder builder) {
    for (AtomicReference<?> slot : stack.arena) {
      builder.expectThat("not null arena slot", slot.get(), is(nullValue()));
    }
  }

  @Factory
  public static <E> IsValidConcurrentLinkedStack<E> validate() {
    return new IsValidConcurrentLinkedStack<E>();
  }
}
