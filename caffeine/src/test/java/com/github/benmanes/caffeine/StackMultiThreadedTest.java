/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.ConcurrentLinkedStackTest.ValidatingStackListener;
import com.github.benmanes.caffeine.testing.Threads;
import com.google.common.collect.ImmutableList;
import com.google.common.testing.SerializableTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(ValidatingStackListener.class)
public final class StackMultiThreadedTest {

  @Test(dataProvider = "queues")
  public void thrash(ConcurrentLinkedStack<Integer> stack) {
    Threads.runTest(stack, operations);
  }

  @DataProvider(name = "queues")
  public Object[][] providesQueues() {
    return new Object[][] {
        { ConcurrentLinkedStack.optimistic() },
        { ConcurrentLinkedStack.linearizable() }};
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  List<BiConsumer<ConcurrentLinkedStack<Integer>, Integer>> operations = ImmutableList.of(
      // Stack
      (c, e) -> c.push(e),
      (c, e) -> c.pop(),
      (c, e) -> c.peek(),

      // Queue
      (c, e) -> c.asLifoQueue().offer(e),
      (c, e) -> c.asLifoQueue().poll(),
      (c, e) -> c.asLifoQueue().peek(),

      // Collection
      (c, e) -> c.size(),
      (c, e) -> c.isEmpty(),
      (c, e) -> c.contains(e),
      (c, e) -> c.containsAll(ImmutableList.of(e, e)),
      (c, e) -> c.add(e),
      (c, e) -> c.addAll(ImmutableList.of(e, e)),
      (c, e) -> c.remove(e),
      (c, e) -> c.removeAll(ImmutableList.of(e, e)),
      (c, e) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          c.retainAll(ImmutableList.of(e, e));
        }
      },
      (c, e) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          c.clear();
        }
      },
      (c, e) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          SerializableTester.reserialize(c);
        }
      });
}
