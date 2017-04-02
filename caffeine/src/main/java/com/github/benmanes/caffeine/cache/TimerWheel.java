/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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

import static java.util.Objects.requireNonNull;

import java.lang.ref.ReferenceQueue;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A hierarchical timer wheel to add, remove, and fire expiration events in amortized O(1) time. The
 * expiration events are deferred until the timer is advanced, which is performed as part of the
 * cache's maintenance cycle.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@NotThreadSafe
final class TimerWheel<K, V> {

  /*
   * A timer wheel [1] stores timer events in buckets on a circular buffer. A bucket represents a
   * coarse time span, e.g. one minute, and holds a doubly-linked list of events. The wheels are
   * structured in a hierarchy (seconds, minutes, hours, days) so that events scheduled in the
   * distant future are cascaded to lower buckets when the wheels rotate. This allows for events
   * to be added, removed, and expired in O(1) time, where expiration occurs for the entire bucket,
   * and penalty of cascading is amortized by the rotations.
   *
   * [1] Hashed and Hierarchical Timing Wheels
   * http://www.cs.columbia.edu/~nahum/w6998/papers/ton97-timing-wheels.pdf
   */

  static final int[] SPOKES = { 64, 64, 32, 4 };
  static final long[] SPANS = {
      ceilingPowerOfTwo(TimeUnit.SECONDS.toNanos(1)),
      ceilingPowerOfTwo(TimeUnit.MINUTES.toNanos(1)),
      ceilingPowerOfTwo(TimeUnit.HOURS.toNanos(1)),
      ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)),
      SPOKES[3] * ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)),
  };
  static final long[] SHIFT = {
      Long.SIZE - Long.numberOfLeadingZeros(SPANS[0] - 1),
      Long.SIZE - Long.numberOfLeadingZeros(SPANS[1] - 1),
      Long.SIZE - Long.numberOfLeadingZeros(SPANS[2] - 1),
      Long.SIZE - Long.numberOfLeadingZeros(SPANS[3] - 1),
  };

  final Predicate<Node<K, V>> evictor;
  final Node<K, V>[][] wheel;

  long nanos;

  @SuppressWarnings({"rawtypes", "unchecked"})
  TimerWheel(Predicate<Node<K, V>> evictor) {
    this.evictor = requireNonNull(evictor);

    wheel = new Node[SPOKES.length][1];
    for (int i = 0; i < wheel.length; i++) {
      wheel[i] = new Node[SPOKES[i]];
      for (int j = 0; j < wheel[i].length; j++) {
        wheel[i][j] = new Sentinel<>();
      }
    }
  }

  /**
   * Advances the timer and evicts entries that have expired.
   *
   * @param currentTimeNanos the current time, in nanoseconds
   */
  public void advance(long currentTimeNanos) {
    long previousTimeNanos = nanos;
    nanos = currentTimeNanos;

    for (int i = 0; i < SHIFT.length; i++) {
      int prevTicks = (int) (previousTimeNanos >>> SHIFT[i]);
      int currentTicks = (int) (currentTimeNanos >>> SHIFT[i]);
      if (prevTicks >= currentTicks) {
        break;
      }
      expire(i, previousTimeNanos, currentTimeNanos);
    }
  }

  /**
   * Expires entries or reschedules into the proper bucket if still active.
   *
   * @param index the wheel being operated on
   * @param previousTimeNanos the previous time, in nanoseconds
   * @param currentTimeNanos the current time, in nanoseconds
   */
  void expire(int index, long previousTimeNanos, long currentTimeNanos) {
    int start, end;
    if ((currentTimeNanos - previousTimeNanos) > SPANS[index + 1]) {
      end = SPOKES[index] - 1;
      start = 0;
    } else {
      long mask = SPANS[index] - 1;
      long previousTicks = (previousTimeNanos >>> SHIFT[index]);
      long currentTicks = (currentTimeNanos >>> SHIFT[index]);

      end = (int) (currentTicks & mask);
      start = (int) (previousTicks & mask);
    }

    Node<K, V>[] timerWheel = wheel[index];
    for (int i = start; i <= end; i++) {
      Node<K, V> node = timerWheel[i].getNextInAccessOrder();
      timerWheel[i].setPreviousInAccessOrder(timerWheel[i]);
      timerWheel[i].setNextInAccessOrder(timerWheel[i]);

      while (node != timerWheel[i]) {
        Node<K, V> next = node.getNextInAccessOrder();
        if ((node.getAccessTime() > currentTimeNanos) || !evictor.test(node)) {
          schedule(node);
        }
        node = next;
      }
    }
  }

  /**
   * Schedules a timer event for the node. If the entry was previously scheduled that event is
   * removed.
   *
   * @param node the entry in the cache
   */
  public void schedule(Node<K, V> node) {
    Node<K, V> sentinel = findBucket(node.getAccessTime());

    unlink(node);
    link(sentinel, node);
  }

  /**
   * Removes a timer event for this entry if present.
   *
   * @param node the entry in the cache
   */
  public void deschedule(Node<K, V> node) {
    unlink(node);
    node.setNextInAccessOrder(null);
    node.setPreviousInAccessOrder(null);
  }

  /**
   * Determines the bucket that the timer event should be added to.
   *
   * @param time the time when the event fires
   * @return the sentinel at the head of the bucket
   */
  Node<K, V> findBucket(long time) {
    long duration = time - nanos;
    for (int i = 0; i < wheel.length; i++) {
      if (duration < SPANS[i + 1]) {
        int ticks = (int) (time >>> SHIFT[i]);
        int index = ticks & (SPOKES[i] - 1);
        return wheel[i][index];
      }
    }

    // Add to the last timer bucket
    int lastWheel = wheel.length - 1;
    int ticks = (int) (nanos >>> SHIFT[lastWheel]) + wheel[lastWheel].length - 1;
    int index = (int) (ticks & (SPANS[lastWheel] - 1));
    return wheel[lastWheel][index];
  }

  /** Adds the entry at the tail of the bucket's list. */
  void link(Node<K, V> sentinel, Node<K, V> node) {
    node.setPreviousInAccessOrder(sentinel.getPreviousInAccessOrder());
    node.setNextInAccessOrder(sentinel);

    sentinel.getPreviousInAccessOrder().setNextInAccessOrder(node);
    sentinel.setPreviousInAccessOrder(node);
  }

  /** Removes the entry from its bucket, if scheduled. */
  void unlink(Node<K, V> node) {
    Node<K, V> prev = node.getPreviousInAccessOrder();
    Node<K, V> next = node.getNextInAccessOrder();
    if (next != null) {
      next.setPreviousInAccessOrder(prev);
    }
    if (prev != null) {
      prev.setNextInAccessOrder(next);
    }
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < wheel.length; i++) {
      Map<Integer, Integer> buckets = new TreeMap<>();
      for (int j = 0; j < wheel[i].length; j++) {
        int events = 0;
        for (Node<K, V> node = wheel[i][j].getNextInAccessOrder();
            node != wheel[i][j]; node = node.getNextInAccessOrder()) {
          events++;
        }
        if (events > 0) {
          buckets.put(j, events);
        }
      }
      builder.append("Wheel #").append(i + 1).append(": ").append(buckets).append('\n');
    }
    return builder.deleteCharAt(builder.length() - 1).toString();
  }

  /** A sentinel for the doubly-linked list in the bucket. */
  static final class Sentinel<K, V> implements Node<K, V> {
    Node<K, V> prev;
    Node<K, V> next;

    Sentinel() {
      prev = next = this;
    }

    @Override public Node<K, V> getPreviousInAccessOrder() {
      return prev;
    }
    @Override public void setPreviousInAccessOrder(@Nullable Node<K, V> prev) {
      this.prev = prev;
    }
    @Override public Node<K, V> getNextInAccessOrder() {
      return next;
    }
    @Override public void setNextInAccessOrder(@Nullable Node<K, V> next) {
      this.next = next;
    }

    @Override public K getKey() { return null; }
    @Override public Object getKeyReference() { throw new UnsupportedOperationException(); }
    @Override public V getValue() { return null; }
    @Override public Object getValueReference() { throw new UnsupportedOperationException(); }
    @Override public void setValue(V value, ReferenceQueue<V> referenceQueue) {}
    @Override public boolean containsValue(Object value) { return false; }
    @Override public boolean isAlive() { return false; }
    @Override public boolean isRetired() { return false; }
    @Override public boolean isDead() { return false; }
    @Override public void retire() {}
    @Override public void die() {}
  }

  static long ceilingPowerOfTwo(long x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1L << -Long.numberOfLeadingZeros(x - 1);
  }
}
