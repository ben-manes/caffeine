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

import static com.github.benmanes.caffeine.cache.TimerWheel.SHIFT;
import static com.github.benmanes.caffeine.cache.TimerWheel.SPANS;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Locale.US;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.ref.ReferenceQueue;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.TimerWheel.Sentinel;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.Streams;
import com.google.common.hash.Hashing;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"ClassEscapesDefinedScope", "GuardedBy"})
public final class TimerWheelTest {
  private static final long[] CLOCKS = {Long.MIN_VALUE, -SPANS[0] + 1, 0L, 0xfffffffc0000000L,
      Long.MAX_VALUE - SPANS[0] + 1, Long.MAX_VALUE, ThreadLocalRandom.current().nextLong()};

  // Use printTimerWheel(timerWheel) to debug

  @Test(dataProvider = "schedule")
  public void schedule(long clock, long duration, int expired) {
    ArgumentCaptor<Node<Int, Int>> captor = ArgumentCaptor.captor();
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);

    timerWheel.nanos = clock;
    for (int timeout : new int[] { 25, 90, 240 }) {
      timerWheel.schedule(new Timer(clock + TimeUnit.SECONDS.toNanos(timeout)));
    }
    timerWheel.advance(cache, clock + duration);
    verify(cache, times(expired)).evictEntry(any(), any(), anyLong());

    for (var node : captor.getAllValues()) {
      assertThat(node.getVariableTime()).isAtMost(clock + duration);
    }
  }

  @Test(dataProvider = "fuzzySchedule")
  public void schedule_fuzzy(long clock, long duration, long[] times) {
    ArgumentCaptor<Node<Int, Int>> captor = ArgumentCaptor.captor();
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    int expired = 0;
    for (long timeout : times) {
      if (timeout <= duration) {
        expired++;
      }
      timerWheel.schedule(new Timer(timeout));
    }
    timerWheel.advance(cache, duration);
    verify(cache, times(expired)).evictEntry(any(), any(), anyLong());

    for (Node<?, ?> node : captor.getAllValues()) {
      assertThat(node.getVariableTime()).isAtMost(duration);
    }
    checkTimerWheel(timerWheel, duration);
  }

  @Test
  public void findBucket_expired() {
    var timerWheel = new TimerWheel<Int, Int>();
    var clock = ThreadLocalRandom.current().nextLong();
    var duration = ThreadLocalRandom.current().nextLong(Long.MIN_VALUE, 0);

    timerWheel.nanos = clock;
    var expected = timerWheel.findBucket(clock);
    var bucket = timerWheel.findBucket(clock + duration);
    assertThat(bucket).isSameInstanceAs(expected);
  }

  @Test(dataProvider = "clock")
  public void advance(long clock) {
    ArgumentCaptor<Node<Int, Int>> captor = ArgumentCaptor.captor();
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);

    timerWheel.nanos = clock;
    timerWheel.schedule(new Timer(timerWheel.nanos + SPANS[0]));

    timerWheel.advance(cache, clock + 13 * SPANS[0]);
    verify(cache).evictEntry(any(), any(), anyLong());
  }

  @Test
  public void advance_overflow() {
    ArgumentCaptor<Node<Int, Int>> captor = ArgumentCaptor.captor();
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);

    timerWheel.nanos = -TimeUnit.DAYS.toNanos(365) / 2;
    timerWheel.schedule(new Timer(timerWheel.nanos + SPANS[0]));

    timerWheel.advance(cache, timerWheel.nanos + TimeUnit.DAYS.toNanos(365));
    verify(cache).evictEntry(any(), any(), anyLong());
  }

  @Test(dataProvider = "clock")
  public void advance_backwards(long clock) {
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    timerWheel.nanos = clock;
    for (int i = 0; i < 1_000; i++) {
      long duration = ThreadLocalRandom.current().nextLong(TimeUnit.DAYS.toNanos(10));
      timerWheel.schedule(new Timer(clock + duration));
    }
    for (int i = 0; i < TimerWheel.BUCKETS.length; i++) {
      timerWheel.advance(cache, clock - 3 * SPANS[i]);
    }

    verifyNoInteractions(cache);
  }

  @Test(dataProvider = "clock")
  public void advance_reschedule(long clock) {
    ArgumentCaptor<Node<Int, Int>> captor = ArgumentCaptor.captor();
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    var t15 = new Timer(clock + TimeUnit.SECONDS.toNanos(15));
    var t80 = new Timer(clock + TimeUnit.SECONDS.toNanos(80));
    timerWheel.schedule(t15);
    timerWheel.schedule(t80);

    // discard T15, T80 in wheel[1]
    timerWheel.advance(cache, clock + TimeUnit.SECONDS.toNanos(45));
    assertThat(captor.getAllValues()).containsExactly(t15);
    assertThat(timerWheel).hasSize(1);

    // verify not discarded, T80 in wheel[0]
    timerWheel.advance(cache, clock + TimeUnit.SECONDS.toNanos(70));
    assertThat(timerWheel).hasSize(1);

    // verify discarded T80
    timerWheel.advance(cache, clock + TimeUnit.SECONDS.toNanos(90));
    assertThat(captor.getAllValues()).containsExactly(t15, t80);
    assertThat(timerWheel).isEmpty();
  }

  @Test
  public void advance_exception() {
    ArgumentCaptor<Node<Int, Int>> captor = ArgumentCaptor.captor();
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    doThrow(new IllegalArgumentException())
        .when(cache).evictEntry(captor.capture(), any(), anyLong());
    var timer = new Timer(timerWheel.nanos + SPANS[1]);

    timerWheel.nanos = 0L;
    timerWheel.schedule(timer);
    assertThrows(IllegalArgumentException.class, () -> timerWheel.advance(cache, Long.MAX_VALUE));
    assertThat(timerWheel.wheel[1][1].getNextInVariableOrder()).isSameInstanceAs(timer);
    assertThat(timerWheel.nanos).isEqualTo(0);
  }

  @Test(dataProvider = "clock")
  public void getExpirationDelay_empty(long clock) {
    var timerWheel = new TimerWheel<Int, Int>();

    timerWheel.nanos = clock;
    assertThat(timerWheel.getExpirationDelay()).isEqualTo(Long.MAX_VALUE);
  }

  @Test(dataProvider = "clock")
  public void getExpirationDelay_firstWheel(long clock) {
    var timerWheel = new TimerWheel<Int, Int>();

    timerWheel.nanos = clock;
    long delay = Duration.ofSeconds(1).toNanos();
    timerWheel.schedule(new Timer(clock + delay));
    assertThat(timerWheel.getExpirationDelay()).isAtMost(SPANS[0]);
  }

  @Test(dataProvider = "clock")
  public void getExpirationDelay_lastWheel(long clock) {
    var timerWheel = new TimerWheel<Int, Int>();

    timerWheel.nanos = clock;
    long delay = Duration.ofDays(14).toNanos();
    timerWheel.schedule(new Timer(clock + delay));
    assertThat(timerWheel.getExpirationDelay()).isAtMost(delay);
  }

  @Test(dataProvider = "clock")
  public void getExpirationDelay_hierarchy(long clock) {
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    long t15 = clock + Duration.ofSeconds(15).toNanos(); // in wheel[0]
    long t80 = clock + Duration.ofSeconds(80).toNanos(); // in wheel[1]
    timerWheel.schedule(new Timer(t15));
    timerWheel.schedule(new Timer(t80));

    long t45 = clock + Duration.ofSeconds(45).toNanos(); // discard T15, T80 in wheel[1]
    timerWheel.advance(cache, t45);

    long t95 = clock + Duration.ofSeconds(95).toNanos(); // in wheel[0], but expires after T80
    timerWheel.schedule(new Timer(t95));

    long expectedDelay = (t80 - t45);
    long delay = timerWheel.getExpirationDelay();
    assertThat(delay).isLessThan(expectedDelay + SPANS[0]); // cascaded T80 in wheel[1]
  }

  @Test(dataProvider = "fuzzySchedule", invocationCount = 25)
  public void getExpirationDelay_fuzzy(long clock, long duration, long[] times) {
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;
    for (long timeout : times) {
      timerWheel.schedule(new Timer(timeout));
    }
    timerWheel.advance(cache, duration);

    long minDelay = Long.MAX_VALUE;
    int minSpan = Integer.MAX_VALUE;
    int minBucket = Integer.MAX_VALUE;
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        var timers = getTimers(timerWheel.wheel[i][j]);
        for (long delay : timers) {
          if (delay < minDelay) {
            minDelay = delay;
            minBucket = j;
            minSpan = i;
          }
        }
      }
    }

    long delay = timerWheel.getExpirationDelay();
    if (minDelay == Long.MAX_VALUE) {
      var format = "delay=%s but minDelay=%s, minSpan=%s, minBucket=%s";
      assertWithMessage(format, delay, minDelay, minSpan, minBucket)
          .that(delay).isEqualTo(Long.MAX_VALUE);
      return;
    }

    long maxError = minDelay + SPANS[minSpan];
    if (maxError > delay) {
      var format = "delay=%s but minDelay=%s, minSpan=%s, minBucket=%s";
      assertWithMessage(format, delay, minDelay, minSpan, minBucket)
          .that(delay).isLessThan(maxError);
    }
  }

  @DataProvider(name = "clock")
  public Iterator<Object> providesClock() {
    return LongStream.of(CLOCKS).mapToObj(Object.class::cast).iterator();
  }

  @DataProvider(name = "schedule")
  public Iterator<Object[]> providesSchedule() {
    var args = new ArrayList<Object[]>();
    for (long clock : CLOCKS) {
      args.add(new Object[] { clock, TimeUnit.SECONDS.toNanos(10), 0 });
      args.add(new Object[] { clock, TimeUnit.MINUTES.toNanos(3),  2 });
      args.add(new Object[] { clock, TimeUnit.MINUTES.toNanos(10), 3 });
    }
    return args.iterator();
  }

  @DataProvider(name = "fuzzySchedule")
  public Object[][] providesFuzzySchedule() {
    long[] times = new long[5_000];
    long clock = ThreadLocalRandom.current().nextLong();
    long bound = clock + TimeUnit.DAYS.toNanos(1) + SPANS[SPANS.length - 1];
    for (int i = 0; i < times.length; i++) {
      times[i] = ThreadLocalRandom.current().nextLong(clock + 1, bound);
    }
    long duration = ThreadLocalRandom.current().nextLong(clock + 1, bound);
    return new Object[][] {{ clock, duration, times }};
  }

  private static void checkTimerWheel(TimerWheel<?, ?> timerWheel, long duration) {
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        for (long timer : getTimers(timerWheel.wheel[i][j])) {
          if (timer <= duration) {
            throw new AssertionError(String.format(US, "wheel[%s][%d] by %ss", i, j,
                TimeUnit.NANOSECONDS.toSeconds(duration - timer)));
          }
        }
      }
    }
  }

  private static List<Long> getTimers(Node<?, ?> sentinel) {
    var timers = new ArrayList<Long>();
    for (var node = sentinel.getNextInVariableOrder();
         node != sentinel; node = node.getNextInVariableOrder()) {
      timers.add(node.getVariableTime());
    }
    return timers;
  }

  @Test(dataProvider = "clock")
  public void reschedule(long clock) {
    ArgumentCaptor<Node<Int, Int>> captor = ArgumentCaptor.captor();
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    var timer = new Timer(clock + TimeUnit.MINUTES.toNanos(15));
    timerWheel.schedule(timer);
    var startBucket = timer.getNextInVariableOrder();

    timer.setVariableTime(clock + TimeUnit.HOURS.toNanos(2));
    timerWheel.reschedule(timer);
    assertThat(timer.getNextInVariableOrder()).isNotSameInstanceAs(startBucket);

    timerWheel.advance(cache, clock + TimeUnit.DAYS.toNanos(1));
    checkEmpty(timerWheel);
  }

  private static void checkEmpty(TimerWheel<Int, Int> timerWheel) {
    for (var wheel : timerWheel.wheel) {
      for (var sentinel : wheel) {
        assertThat(sentinel.getNextInVariableOrder()).isSameInstanceAs(sentinel);
        assertThat(sentinel.getPreviousInVariableOrder()).isSameInstanceAs(sentinel);
      }
    }
  }

  @Test(dataProvider = "clock")
  public void deschedule(long clock) {
    var timerWheel = new TimerWheel<Int, Int>();

    var timer = new Timer(clock + 100);
    timerWheel.nanos = clock;
    timerWheel.schedule(timer);
    timerWheel.deschedule(timer);
    assertThat(timer.getNextInVariableOrder()).isNull();
    assertThat(timer.getPreviousInVariableOrder()).isNull();
  }

  @Test(dataProvider = "clock")
  public void deschedule_notScheduled(long clock) {
    var timerWheel = new TimerWheel<Int, Int>();

    timerWheel.nanos = clock;
    timerWheel.deschedule(new Timer(clock + 100));
  }

  @Test(dataProvider = "fuzzySchedule")
  public void deschedule_fuzzy(long clock, long nanos, long[] times) {
    var timerWheel = new TimerWheel<Int, Int>();
    var timers = new ArrayList<Timer>();
    timerWheel.nanos = clock;

    for (long timeout : times) {
      var timer = new Timer(timeout);
      timerWheel.schedule(timer);
      timers.add(timer);
    }
    for (Timer timer : timers) {
      timerWheel.deschedule(timer);
    }
    checkTimerWheel(timerWheel, nanos);
  }

  @Test(dataProvider = "clock")
  public void expire_reschedule(long clock) {
    ArgumentCaptor<Node<Int, Int>> captor = ArgumentCaptor.captor();
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    when(cache.evictEntry(captor.capture(), any(), anyLong())).then(invocation -> {
      Timer timer = invocation.getArgument(0);
      timer.setVariableTime(timerWheel.nanos + 100);
      return false;
    });

    timerWheel.nanos = clock;
    timerWheel.schedule(new Timer(clock + 100));
    timerWheel.advance(cache, clock + SPANS[0]);

    verify(cache).evictEntry(any(), any(), anyLong());
    assertThat(captor.getValue().getNextInVariableOrder()).isNotNull();
    assertThat(captor.getValue().getPreviousInVariableOrder()).isNotNull();
  }

  @Test(dataProvider = "cascade")
  public void cascade(long clock, long duration, long timeout, int span) {
    BoundedLocalCache<Int, Int> cache = Mockito.mock();
    var timerWheel = new TimerWheel<Int, Int>();

    timerWheel.nanos = clock;
    timerWheel.schedule(new Timer(clock + timeout));
    timerWheel.advance(cache, clock + duration);

    int count = 0;
    for (int i = 0; i <= span; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        count += getTimers(timerWheel.wheel[i][j]).size();
      }
    }
    assertThat(count).isEqualTo(1);
  }

  @DataProvider(name = "cascade")
  public Iterator<Object[]> providesCascade() {
    var args = new ArrayList<Object[]>();
    for (int i = 1; i < SPANS.length - 1; i++) {
      long span = SPANS[i];
      long timeout = ThreadLocalRandom.current().nextLong(span + 1, 2 * span);
      long duration = ThreadLocalRandom.current().nextLong(span + 1, timeout - 1);
      for (long clock : CLOCKS) {
        args.add(new Object[] { clock, duration, timeout, i});
      }
    }
    return args.iterator();
  }

  @Test(dataProvider = "iterator")
  public void iterator_hasNext(
      TimerWheel<Int, Int> timerWheel, Iterable<Node<Int, Int>> iterable) {
    var iterator = iterable.iterator();
    assertThat(iterator.hasNext()).isFalse();

    timerWheel.schedule(new Timer(1));
    assertThat(iterator.hasNext()).isFalse();

    iterator = iterable.iterator();
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.hasNext()).isTrue();

    iterator.next();
    assertThat(iterator.hasNext()).isFalse();

    assertThrows(NoSuchElementException.class, iterator::next);
  }

  @DataProvider(name = "iterator")
  @SuppressWarnings("MethodReferenceUsage")
  public Object[][] providesIterators() {
    var ascendingTimerWheel = new TimerWheel<Int, Int>();
    var descendingTimerWheel = new TimerWheel<Int, Int>();
    Iterable<Node<Int, Int>> ascending = ascendingTimerWheel;
    Iterable<Node<Int, Int>> descending = descendingTimerWheel::descendingIterator;
    return new Object[][] {{ascendingTimerWheel, ascending}, {descendingTimerWheel, descending}};
  }

  @Test(dataProvider = "clock")
  public void iterator_fixed(long clock) {
    var timerWheel = new TimerWheel<Int, Int>();

    timerWheel.nanos = clock;
    var input = IntStream.range(0, 21).mapToObj(i -> {
      var timer = new Timer(clock + TimeUnit.SECONDS.toNanos(2L << i));
      timerWheel.schedule(timer);
      return timer.getKey();
    }).collect(toImmutableList());

    var ascending = Streams.stream(timerWheel.iterator())
        .limit(input.size() + 1).map(Node::getKey).collect(toImmutableList());
    assertThat(ascending).containsExactlyElementsIn(input).inOrder();

    var descending = Streams.stream(timerWheel.descendingIterator())
        .limit(input.size() + 1).map(Node::getKey).collect(toImmutableList());
    assertThat(descending).containsExactlyElementsIn(input.reverse()).inOrder();
  }

  @Test(invocationCount = 25)
  public void iterator_random() {
    var timerWheel = new TimerWheel<Int, Int>();

    int range = ThreadLocalRandom.current().nextInt(0, 1000);
    timerWheel.nanos = ThreadLocalRandom.current().nextLong(
        TimeUnit.MILLISECONDS.toNanos(500), TimeUnit.DAYS.toNanos(7));
    var timers = IntStream.range(0, range).mapToLong(i -> {
      return ThreadLocalRandom.current().nextLong(
          TimeUnit.SECONDS.toNanos(1), TimeUnit.DAYS.toNanos(7));
    }).sorted().mapToObj(Timer::new).collect(toImmutableList());
    for (var timer : timers) {
      timerWheel.schedule(timer);
    }

    var keys = timers.stream().map(Timer::getKey).collect(toImmutableList());
    var ascending = Streams.stream(timerWheel.iterator())
        .limit(range + 1).map(Node::getKey).collect(toImmutableList());
    assertThat(ascending).containsExactlyElementsIn(keys);
    var ascendingSnapshot = snapshot(timerWheel, /* ascending= */ true).stream()
        .map(TimerWheelTest::longToInt).collect(toImmutableList());
    assertThat(ascending).containsExactlyElementsIn(ascendingSnapshot).inOrder();

    var descending = Streams.stream(timerWheel.descendingIterator())
        .limit(range + 1).map(Node::getKey).collect(toImmutableList());
    assertThat(descending).containsExactlyElementsIn(keys);
    var descendingSnapshot = snapshot(timerWheel, /* ascending= */ false).stream()
        .map(TimerWheelTest::longToInt).collect(toImmutableList());
    assertThat(descending).containsExactlyElementsIn(descendingSnapshot).inOrder();
  }

  @Test
  public void sentinel_ignored() {
    var node = new Sentinel<>();
    node.setValue(new Object(), null);
    node.retire();
    node.die();

    assertThat(node.getKey()).isNull();
    assertThat(node.getValue()).isNull();
    assertThat(node.containsValue(new Object())).isFalse();
    assertThat(node.isAlive()).isFalse();
    assertThat(node.isRetired()).isFalse();
    assertThat(node.isDead()).isFalse();
  }

  @Test
  public void sentinel_unsupported() {
    var node = new Sentinel<>();
    assertThrows(UnsupportedOperationException.class, node::getKeyReference);
    assertThrows(UnsupportedOperationException.class, node::getValueReference);
  }

  /** Returns a snapshot roughly ordered by the expiration time. */
  private static List<Long> snapshot(TimerWheel<Int, Int> timerWheel, boolean ascending) {
    var snapshot = new ArrayList<Long>();
    int startLevel = ascending ? 0 : timerWheel.wheel.length - 1;
    Function<Node<?, ?>, Node<?, ?>> successor =
        ascending ? Node::getNextInVariableOrder : Node::getPreviousInVariableOrder;
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      int indexOffset = ascending ? i : -i;
      int index = startLevel + indexOffset;
      int ticks = (int) (timerWheel.nanos >>> SHIFT[index]);
      int bucketMask = (timerWheel.wheel[index].length - 1);
      int startBucket = (ticks & bucketMask) + (ascending ? 1 : 0);
      for (int j = 0; j < timerWheel.wheel[index].length; j++) {
        int bucketOffset = ascending ? j : -j;
        var sentinel = timerWheel.wheel[index][(startBucket + bucketOffset) & bucketMask];
        for (var node = successor.apply(sentinel); node != sentinel; node = successor.apply(node)) {
          if ((node.getKey() != null) && (node.getValue() != null) && node.isAlive()) {
            snapshot.add(node.getVariableTime());
          }
        }
      }
    }
    return snapshot;
  }

  @SuppressWarnings("SystemOut")
  static void printTimerWheel(TimerWheel<?, ?> timerWheel) {
    var builder = new StringBuilder();
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      int ticks = (int) (timerWheel.nanos >>> SHIFT[i]);
      int bucketMask = (timerWheel.wheel[i].length - 1);
      int index = (ticks & bucketMask);
      var buckets = new TreeMap<String, List<Object>>();
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        var events = new ArrayList<Object>();
        for (var node = timerWheel.wheel[i][j].getNextInVariableOrder();
             node != timerWheel.wheel[i][j]; node = node.getNextInVariableOrder()) {
          events.add(node.getKey());
        }
        if (j == index) {
          buckets.put("*" + j, events);
        } else if (!events.isEmpty()) {
          buckets.put(Integer.toString(j), events);
        }
      }
      builder.append(" - Wheel #").append(i + 1).append(": ").append(buckets).append('\n');
    }
    System.err.printf(US, "%nCurrent state:%n%s%n%n", builder.deleteCharAt(builder.length() - 1));
  }

  private static Int longToInt(long l) {
    return Int.valueOf(Hashing.murmur3_128().hashLong(l).asInt());
  }

  @NullUnmarked
  private static final class Timer extends Node<Int, Int> {
    @Nullable Node<Int, Int> prev;
    @Nullable Node<Int, Int> next;
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
    @Override public Node<Int, Int> getPreviousInVariableOrder() {
      return prev;
    }
    @Override public void setPreviousInVariableOrder(@Nullable Node<Int, Int> prev) {
      this.prev = prev;
    }
    @Override public Node<Int, Int> getNextInVariableOrder() {
      return next;
    }
    @Override public void setNextInVariableOrder(@Nullable Node<Int, Int> next) {
      this.next = next;
    }

    @Override public Int getKey() { return longToInt(variableTime); }
    @Override public Object getKeyReference() { return null; }
    @Override public Int getValue() { return getKey().negate(); }
    @Override public Object getValueReference() { return null; }
    @Override public void setValue(Int value, ReferenceQueue<Int> referenceQueue) {}
    @Override public boolean containsValue(Object value) { return false; }
    @Override public boolean isAlive() { return true; }
    @Override public boolean isRetired() { return false; }
    @Override public boolean isDead() { return false; }
    @Override public void retire() {}
    @Override public void die() {}
  }
}
