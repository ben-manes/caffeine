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

import java.util.Collection;
import java.util.Queue;

import com.google.common.collect.testing.CollectionTestSuiteBuilder;
import com.google.common.collect.testing.MinimalCollection;
import com.google.common.collect.testing.QueueTestSuiteBuilder;
import com.google.common.collect.testing.TestStringCollectionGenerator;
import com.google.common.collect.testing.TestStringQueueGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Guava testlib map tests for {@link EliminationStack}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ConcurrentLinkedStackTests extends TestCase {

  public static Test suite() {
    TestSuite suite = stackTest(true);
    suite.addTest(stackTest(false));
    suite.addTest(queueTest(true));
    suite.addTest(queueTest(false));
    return suite;
  }

  public static TestSuite stackTest(boolean optimistic) {
    return CollectionTestSuiteBuilder
        .using(new TestStringCollectionGenerator() {
            @Override public Collection<String> create(String[] elements) {
              ConcurrentLinkedStack<String> stack = optimistic
                  ? ConcurrentLinkedStack.optimistic()
                  : ConcurrentLinkedStack.linearizable();
              stack.addAll(MinimalCollection.of(elements));
              return stack;
            }
          })
        .named(ConcurrentLinkedStack.class.getSimpleName())
        .withFeatures(
            CollectionFeature.ALLOWS_NULL_QUERIES,
            CollectionFeature.GENERAL_PURPOSE,
            CollectionFeature.SERIALIZABLE,
            CollectionSize.ANY)
        .createTestSuite();
  }

  public static TestSuite queueTest(boolean optimistic) {
    return QueueTestSuiteBuilder
        .using(new TestStringQueueGenerator() {
            @Override public Queue<String> create(String[] elements) {
              ConcurrentLinkedStack<String> stack = optimistic
                  ? ConcurrentLinkedStack.optimistic()
                  : ConcurrentLinkedStack.linearizable();
              stack.addAll(MinimalCollection.of(elements));
              return stack.asLifoQueue();
            }
          })
        .named(ConcurrentLinkedStack.class.getSimpleName())
        .withFeatures(
            CollectionFeature.ALLOWS_NULL_QUERIES,
            CollectionFeature.GENERAL_PURPOSE,
            CollectionFeature.SERIALIZABLE,
            CollectionSize.ANY)
        .createTestSuite();
  }
}
