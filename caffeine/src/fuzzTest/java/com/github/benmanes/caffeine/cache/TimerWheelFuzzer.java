/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.TimerWheel.SPANS;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;
import org.mockito.Mockito;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.errorprone.annotations.Var;

/**
 * A fuzzer that exercises the {@link TimerWheel} by generating random sequences of schedule,
 * deschedule, and advance operations. The expired items are validated against a reference
 * and the internal linked list integrity is checked after each operation.
 */
final class TimerWheelFuzzer {
  private static final Operation[] OPERATIONS = Operation.values();
  /** The maximum time span supported by the timer wheel. */
  private static final long MAX_SPAN = SPANS[SPANS.length - 1];

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  @FuzzTest(maxDuration = "5m")
  @SuppressWarnings("GuardedBy")
  void timerWheel(FuzzedDataProvider data) {
    @Var long clock = data.consumeLong();
    var timerWheel = new TimerWheel<>();
    timerWheel.nanos = clock;

    // Reference model: the set of timers currently in the wheel
    var allTimers = new ArrayList<Timer>();
    var inWheel = new HashSet<Timer>();

    BoundedLocalCache<Object, Object> cache = Mockito.mock();
    var expired = new ArrayList<Node<Object, Object>>();
    when(cache.evictEntry(any(), any(), anyLong())).then(invocation -> {
      expired.add(invocation.getArgument(0));
      return true;
    });

    int operations = data.consumeInt(0, 200);
    for (int i = 0; i < operations; i++) {
      long maxOffset = maxSafeOffset(clock);
      var operation = OPERATIONS[data.consumeInt(0, OPERATIONS.length - 1)];
      switch (operation) {
        case SCHEDULE: {
          schedule(data, clock, timerWheel, allTimers, inWheel, maxOffset);
          break;
        }
        case DESCHEDULE: {
          deschedule(data, timerWheel, allTimers, inWheel);
          break;
        }
        case ADVANCE: {
          clock = advance(data, clock, timerWheel, inWheel, cache, expired, maxOffset);
          break;
        }
      }

      // Validate linked list integrity after every operation
      checkLinkedListIntegrity(timerWheel);

      // Validate that the number of nodes in the wheel matches the reference model
      assertWithMessage("Wheel node count should match reference model")
          .that(countNodes(timerWheel)).isEqualTo(inWheel.size());
    }

    // Final advance: move clock far enough to expire all remaining entries. Only assert
    // completeness when there's enough room for a full MAX_SPAN advance, since the wheel
    // needs a full rotation to cascade and expire all levels.
    if (!inWheel.isEmpty() && (maxSafeOffset(clock) >= MAX_SPAN)) {
      expired.clear();
      timerWheel.advance(cache, clock + MAX_SPAN);

      for (var node : expired) {
        inWheel.remove(node);
      }
      assertWithMessage("All entries should be expired after advancing past MAX_SPAN")
          .that(inWheel).isEmpty();
      assertWithMessage("Wheel should be empty after final advance")
          .that(countNodes(timerWheel)).isEqualTo(0);
      checkLinkedListIntegrity(timerWheel);
    }
  }

  private static long advance(FuzzedDataProvider data, long clock,
      TimerWheel<Object, Object> timerWheel, Set<Timer> inWheel,
      BoundedLocalCache<Object, Object> cache, List<Node<Object, Object>> expired, long maxOffset) {
    long advance = data.consumeLong(0, maxOffset);
    long newClock = clock + advance;

    expired.clear();
    timerWheel.advance(cache, newClock);

    // Validate that every expired entry must have variableTime <= newClock (no premature eviction)
    for (var node : expired) {
      long time = node.getVariableTime();
      assertWithMessage(
          "Expired entry should not be in the future (time=%s, clock=%s)", time, newClock)
              .that(time - newClock).isAtMost(0L);
    }

    // Update the reference model
    inWheel.removeAll(expired);
    return newClock;
  }

  private static void deschedule(FuzzedDataProvider data, TimerWheel<Object, Object> timerWheel,
      List<Timer> allTimers, Set<Timer> inWheel) {
    if (!allTimers.isEmpty()) {
      int index = data.consumeInt(0, allTimers.size() - 1);
      var timer = allTimers.get(index);
      timerWheel.deschedule(timer);
      assertThat(timer.getNextInVariableOrder()).isNull();
      assertThat(timer.getPreviousInVariableOrder()).isNull();
      inWheel.remove(timer);
    }
  }

  private static void schedule(FuzzedDataProvider data, long clock,
      TimerWheel<Object, Object> timerWheel, List<Timer> allTimers, Set<Timer> inWheel,
      long maxOffset) {
    long offset = data.consumeLong(0, maxOffset);
    long time = clock + offset;
    var timer = new Timer(time);
    allTimers.add(timer);
    timerWheel.schedule(timer);
    inWheel.add(timer);
  }

  /** Returns the maximum offset that can be added to the clock without overflowing {@code long}. */
  private static long maxSafeOffset(long clock) {
    // When clock < 0, headroom to Long.MAX_VALUE exceeds MAX_SPAN so we just cap at MAX_SPAN
    return (clock >= 0) ? Math.min(MAX_SPAN, Long.MAX_VALUE - clock) : MAX_SPAN;
  }

  /** Validates the doubly-linked list integrity in every bucket of the timer wheel. */
  private static void checkLinkedListIntegrity(TimerWheel<?, ?> timerWheel) {
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        var sentinel = timerWheel.wheel[i][j];

        // Walk forward from sentinel and verify backward links
        @Var var node = sentinel.getNextInVariableOrder();
        @Var var prev = sentinel;
        @Var int count = 0;
        int maxSize = 1_000_000; // safety bound
        while (node != sentinel) {
          assertWithMessage("Forward link at wheel[%s][%s], position %s: "
              + "node.prev should point to the previous node", i, j, count)
              .that(node.getPreviousInVariableOrder()).isSameInstanceAs(prev);
          prev = node;
          node = node.getNextInVariableOrder();
          count++;
          assertWithMessage(
              "Linked list at wheel[%s][%s] exceeds safety bound; possible cycle detected", i, j)
                  .that(count).isAtMost(maxSize);
        }

        // The sentinel's prev should be the last node in the chain
        assertWithMessage("Sentinel.prev at wheel[%s][%s] should point to "
            + "the tail of the list", i, j)
            .that(sentinel.getPreviousInVariableOrder()).isSameInstanceAs(prev);
      }
    }
  }

  /** Counts the total number of non-sentinel nodes across all buckets of the timer wheel. */
  private static int countNodes(TimerWheel<?, ?> timerWheel) {
    @Var int total = 0;
    for (var wheel : timerWheel.wheel) {
      for (var sentinel : wheel) {
        for (var node = sentinel.getNextInVariableOrder();
             node != sentinel; node = node.getNextInVariableOrder()) {
          total++;
        }
      }
    }
    return total;
  }

  private enum Operation { SCHEDULE, DESCHEDULE, ADVANCE }

  @NullUnmarked
  private static final class Timer extends Node<@NonNull Object, @NonNull Object> {
    @Nullable Node<@NonNull Object, @NonNull Object> prev;
    @Nullable Node<@NonNull Object, @NonNull Object> next;
    long variableTime;

    Timer(long accessTime) {
      setVariableTime(accessTime);
    }

    @Override public long getVariableTime() {
      return variableTime;
    }
    @Override public void setVariableTime(long variableTime) {
      this.variableTime = variableTime;
    }
    @Override public Node<@NonNull Object, @NonNull Object> getPreviousInVariableOrder() {
      return prev;
    }
    @Override public void setPreviousInVariableOrder(
        @Nullable Node<@NonNull Object, @NonNull Object> prev) {
      this.prev = prev;
    }
    @Override public Node<@NonNull Object, @NonNull Object> getNextInVariableOrder() {
      return next;
    }
    @Override public void setNextInVariableOrder(
        @Nullable Node<@NonNull Object, @NonNull Object> next) {
      this.next = next;
    }

    @Override public Object getKey() { return (int) variableTime; }
    @Override public Object getKeyReference() { throw new UnsupportedOperationException(); }
    @Override public Object getKeyReferenceOrNull() { throw new UnsupportedOperationException(); }
    @Override public Object getValue() { return null; }
    @Override public Object getValueReference() { throw new UnsupportedOperationException(); }
    @Override public void setValue(Object value, ReferenceQueue<Object> referenceQueue) {}
    @Override public boolean containsValue(@NonNull Object value) { return false; }
    @Override public boolean isAlive() { return true; }
    @Override public boolean isRetired() { return false; }
    @Override public boolean isDead() { return false; }
    @Override public void retire() {}
    @Override public void die() {}
  }
}
