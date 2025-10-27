/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.github.benmanes.caffeine.cache.AccessOrderDeque.AccessOrder;
import com.github.benmanes.caffeine.cache.WriteOrderDeque.WriteOrder;
import com.google.common.base.MoreObjects;
import com.google.common.collect.testing.MinimalCollection;
import com.google.common.collect.testing.QueueTestSuiteBuilder;
import com.google.common.collect.testing.SampleElements;
import com.google.common.collect.testing.TestQueueGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Guava testlib tests for the {@link LinkedDeque}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LinkedDequeTests extends TestCase {
  static final LinkedValue a = new LinkedValue("a");
  static final LinkedValue b = new LinkedValue("b");
  static final LinkedValue c = new LinkedValue("c");
  static final LinkedValue d = new LinkedValue("d");
  static final LinkedValue e = new LinkedValue("e");

  // Due to stateful elements, tests calling resetCollection() for a comparable iterator will
  // cause unexpected mutations. Instead, a different collection type should be used for comparison
  static final ThreadLocal<Boolean> useTarget = ThreadLocal.withInitial(() -> false);

  @SuppressWarnings("PMD.JUnit4SuitesShouldUseSuiteAnnotation")
  public static Test suite() {
    var suite = new TestSuite();
    suite.addTest(newTestSuite("AccessOrderDeque", AccessOrderDeque::new));
    suite.addTest(newTestSuite("WriteOrderDeque", WriteOrderDeque::new));
    return suite;
  }

  private static Test newTestSuite(String name, Supplier<LinkedDeque<LinkedValue>> supplier) {
    return QueueTestSuiteBuilder
        .using(new TestLinkedValueGenerator() {
          @Override public Queue<LinkedValue> create(LinkedValue[] elements) {
            var deque = useTarget.get() ? supplier.get() : new ArrayDeque<LinkedValue>();
            deque.addAll(MinimalCollection.of(elements));
            useTarget.set(false);
            return deque;
          }
        })
        .named(name)
        .withFeatures(
            CollectionFeature.FAILS_FAST_ON_CONCURRENT_MODIFICATION,
            CollectionFeature.ALLOWS_NULL_QUERIES,
            CollectionFeature.GENERAL_PURPOSE,
            CollectionFeature.KNOWN_ORDER,
            CollectionSize.ANY)
        .withSetUp(() -> useTarget.set(true))
        .withTearDown(() -> {
          List.of(a, b, c, d, e).forEach(value -> {
            value.setNextInAccessOrder(null);
            value.setPreviousInAccessOrder(null);
          });
        })
        .createTestSuite();
  }

  /** See TestStringQueueGenerator */
  abstract static class TestLinkedValueGenerator implements TestQueueGenerator<LinkedValue> {

    @Override
    public SampleElements<LinkedValue> samples() {
      return new SampleElements<>(b, a, c, d, e);
    }

    @Override
    public Queue<LinkedValue> create(Object... elements) {
      var array = new LinkedValue[elements.length];
      @Var int i = 0;
      for (Object e : elements) {
        array[i++] = (LinkedValue) e;
      }
      return create(array);
    }

    protected abstract Queue<LinkedValue> create(LinkedValue[] elements);

    @Override public LinkedValue[] createArray(int length) {
      return new LinkedValue[length];
    }
    @CanIgnoreReturnValue
    @Override public List<LinkedValue> order(List<LinkedValue> insertionOrder) {
      return insertionOrder;
    }
  }

  static final class LinkedValue implements AccessOrder<LinkedValue>, WriteOrder<LinkedValue> {
    final String value;

    @Nullable LinkedValue prev;
    @Nullable LinkedValue next;

    LinkedValue(String value) {
      this.value = value;
    }

    @Override
    public @Nullable LinkedValue getPreviousInAccessOrder() {
      return prev;
    }

    @Override
    public void setPreviousInAccessOrder(@Nullable LinkedValue prev) {
      this.prev = prev;
    }

    @Override
    public @Nullable LinkedValue getNextInAccessOrder() {
      return next;
    }

    @Override
    public void setNextInAccessOrder(@Nullable LinkedValue next) {
      this.next = next;
    }

    @Override
    public @Nullable LinkedValue getPreviousInWriteOrder() {
      return prev;
    }

    @Override
    public void setPreviousInWriteOrder(@Nullable LinkedValue prev) {
      this.prev = prev;
    }

    @Override
    public @Nullable LinkedValue getNextInWriteOrder() {
      return next;
    }

    @Override
    public void setNextInWriteOrder(@Nullable LinkedValue next) {
      this.next = next;
    }

    @Override
    public boolean equals(Object o) {
      return (o instanceof LinkedValue) && value.equals(((LinkedValue) o).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("value", value)
          .add("prev", (prev == null) ? null : prev.value)
          .add("next", (next == null) ? null : next.value)
          .toString();
    }
  }
}
