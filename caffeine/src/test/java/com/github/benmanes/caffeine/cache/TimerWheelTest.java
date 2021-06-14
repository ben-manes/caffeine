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

import static com.github.benmanes.caffeine.cache.TimerWheel.SPANS;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.ref.ReferenceQueue;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.primitives.Longs;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
@SuppressWarnings("GuardedBy")
public final class TimerWheelTest {
  private static final Random random = new Random();
  private static final long[] CLOCKS = { -SPANS[0] + 1, 0L, 0xfffffffc0000000L,
      Long.MAX_VALUE - SPANS[0] + 1, random.nextLong() };

  @Captor ArgumentCaptor<Node<Long, Long>> captor;
  @Mock BoundedLocalCache<Long, Long> cache;
  TimerWheel<Long, Long> timerWheel;
  AutoCloseable mocks;

  @BeforeMethod
  public void beforeMethod() {
    mocks = MockitoAnnotations.openMocks(this);
    timerWheel = new TimerWheel<>(cache);

    Reset.setThreadLocalRandom(random.nextInt(), random.nextInt());
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    mocks.close();
  }

  @Test(dataProvider = "schedule")
  public void schedule(long clock, long duration, int expired) {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);

    timerWheel.nanos = clock;
    for (int timeout : new int[] { 25, 90, 240 }) {
      timerWheel.schedule(new Timer(clock + TimeUnit.SECONDS.toNanos(timeout)));
    }
    timerWheel.advance(clock + duration);
    verify(cache, times(expired)).evictEntry(any(), any(), anyLong());

    for (var node : captor.getAllValues()) {
      assertThat(node.getVariableTime(), is(lessThan(clock + duration)));
    }
  }

  @Test(dataProvider = "fuzzySchedule")
  public void schedule_fuzzy(long clock, long duration, long[] times) {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    int expired = 0;
    for (long timeout : times) {
      if (timeout <= duration) {
        expired++;
      }
      timerWheel.schedule(new Timer(timeout));
    }
    timerWheel.advance(duration);
    verify(cache, times(expired)).evictEntry(any(), any(), anyLong());

    for (Node<?, ?> node : captor.getAllValues()) {
      assertThat(node.getVariableTime(), is(lessThan(duration)));
    }
    checkTimerWheel(duration);
  }


  @Test(dataProvider = "clock")
  public void advance(long clock) {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);

    timerWheel.nanos = clock;
    timerWheel.schedule(new Timer(timerWheel.nanos + SPANS[0]));

    timerWheel.advance(clock + 13 * SPANS[0]);
    verify(cache).evictEntry(any(), any(), anyLong());
  }

  @Test
  public void advance_exception() {
    Mockito.doThrow(new IllegalStateException())
        .when(cache).evictEntry(captor.capture(), any(), anyLong());
    var timer = new Timer(timerWheel.nanos + SPANS[1]);

    timerWheel.nanos = 0L;
    timerWheel.schedule(timer);
    try {
      timerWheel.advance(Long.MAX_VALUE);
      Assert.fail();
    } catch (IllegalStateException e) {
      assertThat(timerWheel.nanos, is(0L));
      assertThat(timerWheel.wheel[1][1].getNextInVariableOrder(), is(sameInstance(timer)));
    }
  }

  @Test(dataProvider = "clock")
  public void getExpirationDelay_empty(long clock) {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    assertThat(timerWheel.getExpirationDelay(), is(Long.MAX_VALUE));
  }

  @Test(dataProvider = "clock")
  public void getExpirationDelay_firstWheel(long clock) {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    long delay = Duration.ofSeconds(1).toNanos();
    timerWheel.schedule(new Timer(clock + delay));
    assertThat(timerWheel.getExpirationDelay(), is(lessThanOrEqualTo(SPANS[0])));
  }

  @Test(dataProvider = "clock")
  public void getExpirationDelay_lastWheel(long clock) {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    long delay = Duration.ofDays(14).toNanos();
    timerWheel.schedule(new Timer(clock + delay));
    assertThat(timerWheel.getExpirationDelay(), is(lessThanOrEqualTo(delay)));
  }

  @Test(dataProvider = "clock")
  public void getExpirationDelay_hierarchy(long clock) {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    long t15 = clock + Duration.ofSeconds(15).toNanos(); // in wheel[0]
    long t80 = clock + Duration.ofSeconds(80).toNanos(); // in wheel[1]
    timerWheel.schedule(new Timer(t15));
    timerWheel.schedule(new Timer(t80));

    long t45 = clock + Duration.ofSeconds(45).toNanos(); // discard T15, T80 in wheel[1]
    timerWheel.advance(t45);

    long t95 = clock + Duration.ofSeconds(95).toNanos(); // in wheel[0], but expires after T80
    timerWheel.schedule(new Timer(t95));

    long expectedDelay = (t80 - t45);
    long delay = timerWheel.getExpirationDelay();
    assertThat(delay, is(lessThan(expectedDelay + SPANS[0]))); // cascaded T80 in wheel[1]
  }

  @Test(dataProvider = "fuzzySchedule", invocationCount = 25)
  public void getExpirationDelay_fuzzy(long clock, long duration, long[] times) {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;
    for (long timeout : times) {
      timerWheel.schedule(new Timer(timeout));
    }
    timerWheel.advance(duration);

    long minDelay = Long.MAX_VALUE;
    int minSpan = Integer.MAX_VALUE;
    int minBucket = Integer.MAX_VALUE;
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        LongArrayList timers = getTimers(timerWheel.wheel[i][j]);
        for (int k = 0; k < timers.size(); k++) {
          long delay = timers.getLong(k);
          if (delay < minDelay) {
            minDelay = delay;
            minBucket = j;
            minSpan = i;
          }
        }
      }
    }

    long delay = timerWheel.getExpirationDelay();
    String msg = String.format("delay=%d but minDelay=%d, minSpan=%d, minBucket=%d",
        delay, minDelay, minSpan, minBucket);
    if (minDelay == Long.MAX_VALUE) {
      assertThat(msg, delay, is(Long.MAX_VALUE));
      return;
    }

    long maxError = minDelay + SPANS[minSpan];
    if (maxError > delay) {
      assertThat(msg, delay, is(lessThan(maxError)));
    }
  }

  @DataProvider(name = "clock")
  public Iterator<Object> providesClock() {
    return Longs.asList(CLOCKS).stream().map(o -> (Object) o).iterator();
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

  private void checkTimerWheel(long duration) {
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        for (long timer : getTimers(timerWheel.wheel[i][j])) {
          if (timer <= duration) {
            throw new AssertionError(String.format("wheel[%s][%d] by %ss", i, j,
                TimeUnit.NANOSECONDS.toSeconds(duration - timer)));
          }
        }
      }
    }
  }

  private LongArrayList getTimers(Node<?, ?> sentinel) {
    var timers = new LongArrayList();
    for (var node = sentinel.getNextInVariableOrder();
         node != sentinel; node = node.getNextInVariableOrder()) {
      timers.add(node.getVariableTime());
    }
    return timers;
  }

  @Test(dataProvider = "clock")
  public void reschedule(long clock) {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    Timer timer = new Timer(clock + TimeUnit.MINUTES.toNanos(15));
    timerWheel.schedule(timer);
    Node<?, ?> startBucket = timer.getNextInVariableOrder();

    timer.setVariableTime(clock + TimeUnit.HOURS.toNanos(2));
    timerWheel.reschedule(timer);
    assertThat(timer.getNextInVariableOrder(), is(not(startBucket)));

    timerWheel.advance(clock + TimeUnit.DAYS.toNanos(1));
    checkEmpty();
  }

  private void checkEmpty() {
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        Node<Long, Long> sentinel = timerWheel.wheel[i][j];
        assertThat(sentinel.getNextInVariableOrder(), is(sentinel));
        assertThat(sentinel.getPreviousInVariableOrder(), is(sentinel));
      }
    }
  }

  @Test(dataProvider = "clock")
  public void deschedule(long clock) {
    var timer = new Timer(clock + 100);
    timerWheel.nanos = clock;
    timerWheel.schedule(timer);
    timerWheel.deschedule(timer);
    assertThat(timer.getNextInVariableOrder(), is(nullValue()));
    assertThat(timer.getPreviousInVariableOrder(), is(nullValue()));
  }

  @Test(dataProvider = "clock")
  public void deschedule_notScheduled(long clock) {
    timerWheel.nanos = clock;
    timerWheel.deschedule(new Timer(clock + 100));
  }

  @Test(dataProvider = "fuzzySchedule")
  public void deschedule_fuzzy(long clock, long nanos, long[] times) {
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
    checkTimerWheel(nanos);
  }

  @Test(dataProvider = "clock")
  public void expire_reschedule(long clock) {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenAnswer(invocation -> {
      var timer = (Timer) invocation.getArgument(0);
      timer.setVariableTime(timerWheel.nanos + 100);
      return false;
    });

    timerWheel.nanos = clock;
    timerWheel.schedule(new Timer(clock + 100));
    timerWheel.advance(clock + SPANS[0]);

    verify(cache).evictEntry(any(), any(), anyLong());
    assertThat(captor.getValue().getNextInVariableOrder(), is(not(nullValue())));
    assertThat(captor.getValue().getPreviousInVariableOrder(), is(not(nullValue())));
  }

  @Test(dataProvider = "cascade")
  public void cascade(long clock, long duration, long timeout, int span) {
    timerWheel.nanos = clock;
    timerWheel.schedule(new Timer(clock + timeout));
    timerWheel.advance(clock + duration);

    int count = 0;
    for (int i = 0; i <= span; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        count += getTimers(timerWheel.wheel[i][j]).size();
      }
    }
    assertThat("\n" + timerWheel.toString(), count, is(1));
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

  @Test(dataProvider = "snapshot")
  public void snapshot(boolean ascending, int limit, long clock, Function<Long, Long> transformer) {
    int count = 21;
    timerWheel.nanos = clock;
    int expected = Math.min(limit, count);
    Comparator<Long> order = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
    var times = IntStream.range(0, count).mapToLong(i -> {
      long time = clock + TimeUnit.SECONDS.toNanos(2L << i);
      timerWheel.schedule(new Timer(time));
      return time;
    }).boxed().sorted(order).collect(toList()).subList(0, expected);

    when(transformer.apply(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
    assertThat(snapshot(ascending, limit, transformer), is(times));
    verify(transformer, times(expected)).apply(anyLong());
  }

  private List<Long> snapshot(boolean ascending, int limit, Function<Long, Long> transformer) {
    return List.copyOf(timerWheel.snapshot(ascending, limit, transformer).keySet());
  }

  @DataProvider(name="snapshot")
  public Iterator<Object[]> providesSnaphot() {
    var scenarios = new ArrayList<Object[]>();
    for (long clock : CLOCKS) {
      for (int limit : new int[] { 10, 100 }) {
        scenarios.addAll(Arrays.asList(
            new Object[] { /* ascending */ true, limit, clock, Mockito.mock(Function.class) },
            new Object[] { /* ascending */ false, limit, clock, Mockito.mock(Function.class) }));
      }
    }
    return scenarios.iterator();
  }

  private static final class Timer extends Node<Long, Long> {
    Node<Long, Long> prev;
    Node<Long, Long> next;
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
    @Override public Node<Long, Long> getPreviousInVariableOrder() {
      return prev;
    }
    @Override public void setPreviousInVariableOrder(@Nullable Node<Long, Long> prev) {
      this.prev = prev;
    }
    @Override public Node<Long, Long> getNextInVariableOrder() {
      return next;
    }
    @Override public void setNextInVariableOrder(@Nullable Node<Long, Long> next) {
      this.next = next;
    }

    @Override public Long getKey() { return variableTime; }
    @Override public Object getKeyReference() { return null; }
    @Override public Long getValue() { return variableTime; }
    @Override public Object getValueReference() { return null; }
    @Override public void setValue(Long value, ReferenceQueue<Long> referenceQueue) {}
    @Override public boolean containsValue(Object value) { return false; }
    @Override public boolean isAlive() { return true; }
    @Override public boolean isRetired() { return false; }
    @Override public boolean isDead() { return false; }
    @Override public void retire() {}
    @Override public void die() {}
  }
}
