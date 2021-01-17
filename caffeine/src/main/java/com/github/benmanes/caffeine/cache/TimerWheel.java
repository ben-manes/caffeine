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

import static com.github.benmanes.caffeine.cache.Caffeine.ceilingPowerOfTwo;
import static com.github.benmanes.caffeine.cache.Caffeine.requireArgument;
import static java.util.Objects.requireNonNull;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A hierarchical timer wheel to add, remove, and fire expiration events in amortized O(1) time. The
 * expiration events are deferred until the timer is advanced, which is performed as part of the
 * cache's maintenance cycle.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("GuardedBy")
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

  static final int[] BUCKETS = { 64, 64, 32, 4, 1 };
  static final long[] SPANS = {
      ceilingPowerOfTwo(TimeUnit.SECONDS.toNanos(1)), // 1.07s
      ceilingPowerOfTwo(TimeUnit.MINUTES.toNanos(1)), // 1.14m
      ceilingPowerOfTwo(TimeUnit.HOURS.toNanos(1)),   // 1.22h
      ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)),    // 1.63d
      BUCKETS[3] * ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)), // 6.5d
      BUCKETS[3] * ceilingPowerOfTwo(TimeUnit.DAYS.toNanos(1)), // 6.5d
  };
  static final long[] SHIFT = {
      Long.numberOfTrailingZeros(SPANS[0]),
      Long.numberOfTrailingZeros(SPANS[1]),
      Long.numberOfTrailingZeros(SPANS[2]),
      Long.numberOfTrailingZeros(SPANS[3]),
      Long.numberOfTrailingZeros(SPANS[4]),
  };

  final BoundedLocalCache<K, V> cache;
  final Node<K, V>[][] wheel;

  long nanos;

  @SuppressWarnings({"rawtypes", "unchecked"})
  TimerWheel(BoundedLocalCache<K, V> cache) {
    this.cache = requireNonNull(cache);

    wheel = new Node[BUCKETS.length][1];
    for (int i = 0; i < wheel.length; i++) {
      wheel[i] = new Node[BUCKETS[i]];
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
    try {
      nanos = currentTimeNanos;
      for (int i = 0; i < SHIFT.length; i++) {
        long previousTicks = (previousTimeNanos >>> SHIFT[i]);
        long currentTicks = (currentTimeNanos >>> SHIFT[i]);
        if ((currentTicks - previousTicks) <= 0L) {
          break;
        }
        expire(i, previousTicks, currentTicks);
      }
    } catch (Throwable t) {
      nanos = previousTimeNanos;
      throw t;
    }
  }

  /**
   * Expires entries or reschedules into the proper bucket if still active.
   *
   * @param index the wheel being operated on
   * @param previousTicks the previous number of ticks
   * @param currentTicks the current number of ticks
   */
  void expire(int index, long previousTicks, long currentTicks) {
    Node<K, V>[] timerWheel = wheel[index];

    int start, end;
    if ((currentTicks - previousTicks) >= timerWheel.length) {
      end = timerWheel.length;
      start = 0;
    } else {
      long mask = SPANS[index] - 1;
      start = (int) (previousTicks & mask);
      end = 1 + (int) (currentTicks & mask);
    }

    int mask = timerWheel.length - 1;
    for (int i = start; i < end; i++) {
      Node<K, V> sentinel = timerWheel[(i & mask)];
      Node<K, V> prev = sentinel.getPreviousInVariableOrder();
      Node<K, V> node = sentinel.getNextInVariableOrder();
      sentinel.setPreviousInVariableOrder(sentinel);
      sentinel.setNextInVariableOrder(sentinel);

      while (node != sentinel) {
        Node<K, V> next = node.getNextInVariableOrder();
        node.setPreviousInVariableOrder(null);
        node.setNextInVariableOrder(null);

        try {
          if (((node.getVariableTime() - nanos) > 0)
              || !cache.evictEntry(node, RemovalCause.EXPIRED, nanos)) {
            schedule(node);
          }
          node = next;
        } catch (Throwable t) {
          node.setPreviousInVariableOrder(sentinel.getPreviousInVariableOrder());
          node.setNextInVariableOrder(next);
          sentinel.getPreviousInVariableOrder().setNextInVariableOrder(node);
          sentinel.setPreviousInVariableOrder(prev);
          throw t;
        }
      }
    }
  }

  /**
   * Schedules a timer event for the node.
   *
   * @param node the entry in the cache
   */
  public void schedule(Node<K, V> node) {
    Node<K, V> sentinel = findBucket(node.getVariableTime());
    link(sentinel, node);
  }

  /**
   * Reschedules an active timer event for the node.
   *
   * @param node the entry in the cache
   */
  public void reschedule(Node<K, V> node) {
    if (node.getNextInVariableOrder() != null) {
      unlink(node);
      schedule(node);
    }
  }

  /**
   * Removes a timer event for this entry if present.
   *
   * @param node the entry in the cache
   */
  public void deschedule(Node<K, V> node) {
    unlink(node);
    node.setNextInVariableOrder(null);
    node.setPreviousInVariableOrder(null);
  }

  /**
   * Determines the bucket that the timer event should be added to.
   *
   * @param time the time when the event fires
   * @return the sentinel at the head of the bucket
   */
  Node<K, V> findBucket(long time) {
    long duration = time - nanos;
    int length = wheel.length - 1;
    for (int i = 0; i < length; i++) {
      if (duration < SPANS[i + 1]) {
        long ticks = (time >>> SHIFT[i]);
        int index = (int) (ticks & (wheel[i].length - 1));
        return wheel[i][index];
      }
    }
    return wheel[length][0];
  }

  /** Adds the entry at the tail of the bucket's list. */
  void link(Node<K, V> sentinel, Node<K, V> node) {
    node.setPreviousInVariableOrder(sentinel.getPreviousInVariableOrder());
    node.setNextInVariableOrder(sentinel);

    sentinel.getPreviousInVariableOrder().setNextInVariableOrder(node);
    sentinel.setPreviousInVariableOrder(node);
  }

  /** Removes the entry from its bucket, if scheduled. */
  void unlink(Node<K, V> node) {
    Node<K, V> next = node.getNextInVariableOrder();
    if (next != null) {
      Node<K, V> prev = node.getPreviousInVariableOrder();
      next.setPreviousInVariableOrder(prev);
      prev.setNextInVariableOrder(next);
    }
  }

  /** Returns the duration until the next bucket expires, or {@link Long.MAX_VALUE} if none. */
  @SuppressWarnings("IntLongMath")
  public long getExpirationDelay() {
    for (int i = 0; i < SHIFT.length; i++) {
      Node<K, V>[] timerWheel = wheel[i];
      long ticks = (nanos >>> SHIFT[i]);

      long spanMask = SPANS[i] - 1;
      int start = (int) (ticks & spanMask);
      int end = start + timerWheel.length;
      int mask = timerWheel.length - 1;
      for (int j = start; j < end; j++) {
        Node<K, V> sentinel = timerWheel[(j & mask)];
        Node<K, V> next = sentinel.getNextInVariableOrder();
        if (next == sentinel) {
          continue;
        }
        long buckets = (j - start);
        long delay = (buckets << SHIFT[i]) - (nanos & spanMask);
        delay = (delay > 0) ? delay : SPANS[i];

        for (int k = i + 1; k < SHIFT.length; k++) {
          long nextDelay = peekAhead(k);
          delay = Math.min(delay, nextDelay);
        }

        return delay;
      }
    }
    return Long.MAX_VALUE;
  }

  /**
   * Returns the duration when the wheel's next bucket expires, or {@link Long.MAX_VALUE} if empty.
   */
  long peekAhead(int i) {
    long ticks = (nanos >>> SHIFT[i]);
    Node<K, V>[] timerWheel = wheel[i];

    long spanMask = SPANS[i] - 1;
    int mask = timerWheel.length - 1;
    int probe = (int) ((ticks  + 1) & mask);
    Node<K, V> sentinel = timerWheel[probe];
    Node<K, V> next = sentinel.getNextInVariableOrder();
    return (next == sentinel) ? Long.MAX_VALUE : (SPANS[i] - (nanos & spanMask));
  }

  /**
   * Returns an unmodifiable snapshot map roughly ordered by the expiration time. The wheels are
   * evaluated in order, but the timers that fall within the bucket's range are not sorted. Beware
   * that obtaining the mappings is <em>NOT</em> a constant-time operation.
   *
   * @param ascending the direction
   * @param limit the maximum number of entries
   * @param transformer a function that unwraps the value
   * @return an unmodifiable snapshot in the desired order
   */
  public Map<K, V> snapshot(boolean ascending, int limit, Function<V, V> transformer) {
    requireArgument(limit >= 0);

    Map<K, V> map = new LinkedHashMap<>(Math.min(limit, cache.size()));
    int startLevel = ascending ? 0 : wheel.length - 1;
    for (int i = 0; i < wheel.length; i++) {
      int indexOffset = ascending ? i : -i;
      int index = startLevel + indexOffset;

      int ticks = (int) (nanos >>> SHIFT[index]);
      int bucketMask = (wheel[index].length - 1);
      int startBucket = (ticks & bucketMask) + (ascending ? 1 : 0);
      for (int j = 0; j < wheel[index].length; j++) {
        int bucketOffset = ascending ? j : -j;
        Node<K, V> sentinel = wheel[index][(startBucket + bucketOffset) & bucketMask];

        for (Node<K, V> node = traverse(ascending, sentinel);
            node != sentinel; node = traverse(ascending, node)) {
          if (map.size() >= limit) {
            break;
          }

          K key = node.getKey();
          V value = transformer.apply(node.getValue());
          if ((key != null) && (value != null) && node.isAlive()) {
            map.put(key, value);
          }
        }
      }
    }
    return Collections.unmodifiableMap(map);
  }

  static <K, V> Node<K, V> traverse(boolean ascending, Node<K, V> node) {
    return ascending ? node.getNextInVariableOrder() : node.getPreviousInVariableOrder();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < wheel.length; i++) {
      Map<Integer, List<K>> buckets = new TreeMap<>();
      for (int j = 0; j < wheel[i].length; j++) {
        List<K> events = new ArrayList<>();
        for (Node<K, V> node = wheel[i][j].getNextInVariableOrder();
            node != wheel[i][j]; node = node.getNextInVariableOrder()) {
          events.add(node.getKey());
        }
        if (!events.isEmpty()) {
          buckets.put(j, events);
        }
      }
      builder.append("Wheel #").append(i + 1).append(": ").append(buckets).append('\n');
    }
    return builder.deleteCharAt(builder.length() - 1).toString();
  }

  /** A sentinel for the doubly-linked list in the bucket. */
  static final class Sentinel<K, V> extends Node<K, V> {
    Node<K, V> prev;
    Node<K, V> next;

    Sentinel() {
      prev = next = this;
    }

    @Override public Node<K, V> getPreviousInVariableOrder() {
      return prev;
    }
    @SuppressWarnings("NullAway")
    @Override public void setPreviousInVariableOrder(@Nullable Node<K, V> prev) {
      this.prev = prev;
    }
    @Override public Node<K, V> getNextInVariableOrder() {
      return next;
    }
    @SuppressWarnings("NullAway")
    @Override public void setNextInVariableOrder(@Nullable Node<K, V> next) {
      this.next = next;
    }

    @Override public @Nullable K getKey() { return null; }
    @Override public Object getKeyReference() { throw new UnsupportedOperationException(); }
    @Override public @Nullable V getValue() { return null; }
    @Override public Object getValueReference() { throw new UnsupportedOperationException(); }
    @Override public void setValue(V value, @Nullable ReferenceQueue<V> referenceQueue) {}
    @Override public boolean containsValue(Object value) { return false; }
    @Override public boolean isAlive() { return false; }
    @Override public boolean isRetired() { return false; }
    @Override public boolean isDead() { return false; }
    @Override public void retire() {}
    @Override public void die() {}
  }
}
