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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
@SuppressWarnings("GuardedBy")
public final class TimerWheelTest {
  private static final Random random = new Random();
  private static final long NOW = random.nextLong();

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
  public void schedule(long nanos, int expired) {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);

    timerWheel.nanos = NOW;
    for (int timeout : new int[] { 25, 90, 240 }) {
      timerWheel.schedule(new Timer(NOW + TimeUnit.SECONDS.toNanos(timeout)));
    }
    timerWheel.advance(NOW + nanos);
    verify(cache, times(expired)).evictEntry(any(), any(), anyLong());

    for (Node<?, ?> node : captor.getAllValues()) {
      assertThat(node.getVariableTime(), is(lessThan(NOW + nanos)));
    }
  }

  @DataProvider(name = "schedule")
  public Object[][] providesSchedule() {
    return new Object[][] {
      { TimeUnit.SECONDS.toNanos(10), 0 },
      { TimeUnit.MINUTES.toNanos(3),  2 },
      { TimeUnit.MINUTES.toNanos(10), 3 }
    };
  }

  @Test(dataProvider = "fuzzySchedule")
  public void schedule_fuzzy(long clock, long nanos, long[] times) {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;

    int expired = 0;
    for (long timeout : times) {
      if (timeout <= nanos) {
        expired++;
      }
      timerWheel.schedule(new Timer(timeout));
    }
    timerWheel.advance(nanos);
    verify(cache, times(expired)).evictEntry(any(), any(), anyLong());

    for (Node<?, ?> node : captor.getAllValues()) {
      assertThat(node.getVariableTime(), is(lessThan(nanos)));
    }
    checkTimerWheel(nanos);
  }

  @Test
  public void getExpirationDelay_empty() {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = NOW;

    assertThat(timerWheel.getExpirationDelay(), is(Long.MAX_VALUE));
  }

  @Test
  public void getExpirationDelay_firstWheel() {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = NOW;

    long delay = Duration.ofSeconds(1).toNanos();
    timerWheel.schedule(new Timer(NOW + delay));
    assertThat(timerWheel.getExpirationDelay(), is(lessThanOrEqualTo(SPANS[0])));
  }

  @Test
  public void getExpirationDelay_lastWheel() {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = NOW;

    long delay = Duration.ofDays(14).toNanos();
    timerWheel.schedule(new Timer(NOW + delay));
    assertThat(timerWheel.getExpirationDelay(), is(lessThanOrEqualTo(delay)));
  }

  @Test
  public void getExpirationDelay_hierarchy() {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = NOW;

    long t15 = NOW + Duration.ofSeconds(15).toNanos(); // in wheel[0]
    long t80 = NOW + Duration.ofSeconds(80).toNanos(); // in wheel[1]
    timerWheel.schedule(new Timer(t15));
    timerWheel.schedule(new Timer(t80));

    long t45 = NOW + Duration.ofSeconds(45).toNanos(); // discard T15, T80 in wheel[1]
    timerWheel.advance(t45);

    long t95 = NOW + Duration.ofSeconds(95).toNanos(); // in wheel[0], but expires after T80
    timerWheel.schedule(new Timer(t95));

    long expectedDelay = (t80 - t45);
    long delay = timerWheel.getExpirationDelay();
    assertThat(delay, is(lessThan(expectedDelay + SPANS[0]))); // cascaded T80 in wheel[1]
  }

  @Test(dataProvider = "fuzzySchedule", invocationCount = 25)
  public void getExpirationDelay_fuzzy(long clock, long nanos, long[] times) {
    when(cache.evictEntry(any(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = clock;
    for (long timeout : times) {
      timerWheel.schedule(new Timer(timeout));
    }
    timerWheel.advance(nanos);

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

  @DataProvider(name = "fuzzySchedule")
  public Object[][] providesFuzzySchedule() {
    long[] times = new long[5_000];
    long clock = ThreadLocalRandom.current().nextLong();
    long bound = clock + TimeUnit.DAYS.toNanos(1) + SPANS[SPANS.length - 1];
    for (int i = 0; i < times.length; i++) {
      times[i] = ThreadLocalRandom.current().nextLong(clock + 1, bound);
    }
    long nanos = ThreadLocalRandom.current().nextLong(clock + 1, bound);
    return new Object[][] {{ clock, nanos, times }};
  }

  private void checkTimerWheel(long nanos) {
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        for (long timer : getTimers(timerWheel.wheel[i][j])) {
          if (timer <= nanos) {
            throw new AssertionError(String.format("wheel[%s][%d] by %ss", i, j,
                TimeUnit.NANOSECONDS.toSeconds(nanos - timer)));
          }
        }
      }
    }
  }

  private LongArrayList getTimers(Node<?, ?> sentinel) {
    LongArrayList timers = new LongArrayList();
    for (Node<?, ?> node = sentinel.getNextInVariableOrder();
        node != sentinel; node = node.getNextInVariableOrder()) {
      timers.add(node.getVariableTime());
    }
    return timers;
  }

  @Test
  public void reschedule() {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);
    timerWheel.nanos = NOW;

    Timer timer = new Timer(NOW + TimeUnit.MINUTES.toNanos(15));
    timerWheel.schedule(timer);
    Node<?, ?> startBucket = timer.getNextInVariableOrder();

    timer.setVariableTime(NOW + TimeUnit.HOURS.toNanos(2));
    timerWheel.reschedule(timer);
    assertThat(timer.getNextInVariableOrder(), is(not(startBucket)));

    timerWheel.advance(NOW + TimeUnit.DAYS.toNanos(1));
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

  @Test
  public void deschedule() {
    Timer timer = new Timer(NOW + 100);
    timerWheel.nanos = NOW;
    timerWheel.schedule(timer);
    timerWheel.deschedule(timer);
    assertThat(timer.getNextInVariableOrder(), is(nullValue()));
    assertThat(timer.getPreviousInVariableOrder(), is(nullValue()));
  }

  @Test
  public void deschedule_notScheduled() {
    timerWheel.nanos = NOW;
    timerWheel.deschedule(new Timer(NOW + 100));
  }

  @Test(dataProvider = "fuzzySchedule")
  public void deschedule_fuzzy(long clock, long nanos, long[] times) {
    List<Timer> timers = new ArrayList<>();
    timerWheel.nanos = clock;

    for (long timeout : times) {
      Timer timer = new Timer(timeout);
      timerWheel.schedule(timer);
      timers.add(timer);
    }
    for (Timer timer : timers) {
      timerWheel.deschedule(timer);
    }
    checkTimerWheel(nanos);
  }

  @Test
  public void expire_reschedule() {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenAnswer(invocation -> {
      Timer timer = (Timer) invocation.getArgument(0);
      timer.setVariableTime(timerWheel.nanos + 100);
      return false;
    });

    timerWheel.nanos = NOW;
    timerWheel.schedule(new Timer(NOW + 100));
    timerWheel.advance(NOW + TimerWheel.SPANS[0]);

    verify(cache).evictEntry(any(), any(), anyLong());
    assertThat(captor.getValue().getNextInVariableOrder(), is(not(nullValue())));
    assertThat(captor.getValue().getPreviousInVariableOrder(), is(not(nullValue())));
  }

  @Test(dataProvider = "cascade")
  public void cascade(long nanos, long timeout, int span) {
    timerWheel.nanos = NOW;
    timerWheel.schedule(new Timer(NOW + timeout));
    timerWheel.advance(NOW + nanos);

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
    List<Object[]> args = new ArrayList<>();
    for (int i = 1; i < TimerWheel.SPANS.length - 1; i++) {
      long duration = TimerWheel.SPANS[i];
      long timeout = ThreadLocalRandom.current().nextLong(duration + 1, 2 * duration);
      long nanos = ThreadLocalRandom.current().nextLong(duration + 1, timeout - 1);
      args.add(new Object[] { nanos, timeout, i});
    }
    return args.iterator();
  }

  @Test(dataProvider = "snapshot")
  public void snapshot(boolean ascending, int limit, long clock, Function<Long, Long> transformer) {
    int count = 21;
    timerWheel.nanos = clock;
    int expected = Math.min(limit, count);
    Comparator<Long> order = ascending ? Comparator.naturalOrder() : Comparator.reverseOrder();
    List<Long> times = IntStream.range(0, count).mapToLong(i -> {
      long time = clock + TimeUnit.SECONDS.toNanos(2 << i);
      timerWheel.schedule(new Timer(time));
      return time;
    }).boxed().sorted(order).collect(toList()).subList(0, expected);

    when(transformer.apply(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
    assertThat(snapshot(ascending, limit, transformer), is(times));
    verify(transformer, times(expected)).apply(anyLong());
  }

  private List<Long> snapshot(boolean ascending, int limit, Function<Long, Long> transformer) {
    return ImmutableList.copyOf(timerWheel.snapshot(ascending, limit, transformer).keySet());
  }

  @DataProvider(name="snapshot")
  public Iterator<Object[]> providesSnaphot() {
    List<Object[]> scenarios = new ArrayList<>();
    for (long clock : new long[] {0L, NOW }) {
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
