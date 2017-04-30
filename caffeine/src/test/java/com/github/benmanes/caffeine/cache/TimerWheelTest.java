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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
public final class TimerWheelTest {
  TimerWheel<Integer, Integer> timerWheel;
  @Mock BoundedLocalCache<Integer, Integer> cache;
  @Captor ArgumentCaptor<Node<Integer, Integer>> captor;

  @BeforeMethod
  public void beforeMethod() {
    MockitoAnnotations.initMocks(this);
    timerWheel = new TimerWheel<>(cache);
  }

  @Test(dataProvider = "schedule")
  public void schedule(long nanos, int expired) {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);

    for (int timeout : new int[] { 25, 90, 240 }) {
      timerWheel.schedule(new Timer(TimeUnit.SECONDS.toNanos(timeout)));
    }
    timerWheel.advance(nanos);
    verify(cache, times(expired)).evictEntry(any(), any(), anyLong());

    for (Node<?, ?> node : captor.getAllValues()) {
      assertThat(node.getVariableTime(), is(lessThan(nanos)));
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

  @DataProvider(name = "fuzzySchedule")
  public Object[][] providesFuzzySchedule() {
    long[] times = new long[5_000];
    long clock = ThreadLocalRandom.current().nextLong();
    long bound = clock + TimeUnit.DAYS.toNanos(10);
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

  private LongList getTimers(Node<?, ?> sentinel) {
    LongList timers = new LongArrayList();
    for (Node<?, ?> node = sentinel.getNextInVariableOrder();
        node != sentinel; node = node.getNextInVariableOrder()) {
      timers.add(node.getVariableTime());
    }
    return timers;
  }

  @Test
  public void reschedule() {
    when(cache.evictEntry(captor.capture(), any(), anyLong())).thenReturn(true);

    Timer timer = new Timer(TimeUnit.MINUTES.toNanos(15));
    timerWheel.schedule(timer);
    Node<?, ?> startBucket = timer.getNextInVariableOrder();

    timer.setVariableTime(TimeUnit.HOURS.toNanos(2));
    timerWheel.reschedule(timer);
    assertThat(timer.getNextInVariableOrder(), is(not(startBucket)));

    timerWheel.advance(TimeUnit.DAYS.toNanos(1));
    checkEmpty();
  }

  private void checkEmpty() {
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        Node<Integer, Integer> sentinel = timerWheel.wheel[i][j];
        assertThat(sentinel.getNextInVariableOrder(), is(sentinel));
        assertThat(sentinel.getPreviousInVariableOrder(), is(sentinel));
      }
    }
  }

  @Test
  public void deschedule() {
    Timer timer = new Timer(100);
    timerWheel.schedule(timer);
    timerWheel.deschedule(timer);
    assertThat(timer.getNextInVariableOrder(), is(nullValue()));
    assertThat(timer.getPreviousInVariableOrder(), is(nullValue()));
  }

  @Test
  public void deschedule_notScheduled() {
    timerWheel.deschedule(new Timer(100));
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

    timerWheel.schedule(new Timer(100));
    timerWheel.advance(TimerWheel.SPANS[0]);

    verify(cache).evictEntry(any(), any(), anyLong());
    assertThat(captor.getValue().getNextInVariableOrder(), is(not(nullValue())));
    assertThat(captor.getValue().getPreviousInVariableOrder(), is(not(nullValue())));
  }

  @Test(dataProvider = "cascade")
  public void cascade(long nanos, long timeout, int span) {
    timerWheel.schedule(new Timer(timeout));
    timerWheel.advance(nanos);

    int count = 0;
    for (int i = 0; i < span; i++) {
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

  private static final class Timer implements Node<Integer, Integer> {
    Node<Integer, Integer> prev;
    Node<Integer, Integer> next;
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
    @Override public Node<Integer, Integer> getPreviousInVariableOrder() {
      return prev;
    }
    @Override public void setPreviousInVariableOrder(@Nullable Node<Integer, Integer> prev) {
      this.prev = prev;
    }
    @Override public Node<Integer, Integer> getNextInVariableOrder() {
      return next;
    }
    @Override public void setNextInVariableOrder(@Nullable Node<Integer, Integer> next) {
      this.next = next;
    }

    @Override public Integer getKey() { return null; }
    @Override public Object getKeyReference() { return null; }
    @Override public Integer getValue() { return null; }
    @Override public Object getValueReference() { return null; }
    @Override public void setValue(Integer value, ReferenceQueue<Integer> referenceQueue) {}
    @Override public boolean containsValue(Object value) { return false; }
    @Override public boolean isAlive() { return false; }
    @Override public boolean isRetired() { return false; }
    @Override public boolean isDead() { return false; }
    @Override public void retire() {}
    @Override public void die() {}
  }
}
