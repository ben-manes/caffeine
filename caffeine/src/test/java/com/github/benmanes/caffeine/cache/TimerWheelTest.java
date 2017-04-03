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

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TimerWheelTest {
  TimerWheel<Integer, Integer> timerWheel;
  @Mock Predicate<Node<Integer, Integer>> evictor;
  @Captor ArgumentCaptor<Node<Integer, Integer>> captor;

  @BeforeMethod
  public void beforeMethod() {
    MockitoAnnotations.initMocks(this);
    timerWheel = new TimerWheel<>(evictor);
  }

  @Test(singleThreaded = true, dataProvider = "schedule")
  public void schedule(long nanos, int expired) {
    when(evictor.test(captor.capture())).thenReturn(true);

    for (int timeout : new int[] { 25, 90, 240 }) {
      timerWheel.schedule(new Timer(TimeUnit.SECONDS.toNanos(timeout)));
    }
    timerWheel.advance(nanos);
    verify(evictor, times(expired)).test(any());

    for (Node<?, ?> node : captor.getAllValues()) {
      assertThat(node.getAccessTime(), is(lessThan(nanos)));
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

  @Test(enabled = false, singleThreaded = true)
  public void cascade() {
    when(evictor.test(captor.capture())).thenReturn(true);

    List<Long> timers = generateTimers();
    for (long time : timers) {
      timerWheel.schedule(new Timer(time));
    }

    long currentTimeNanos = TimeUnit.HOURS.toNanos(3);
    checkTimerWheel(timers, currentTimeNanos);
  }

  private void checkTimerWheel(List<Long> timers, long currentTimeNanos) {
    TimerWheel<Integer, Integer> expected = new TimerWheel<>(evictor);
    System.out.println("Initial\n" + timerWheel);

    timerWheel.advance(currentTimeNanos);
    expected.advance(currentTimeNanos);

    for (long time : timers) {
      if (time > currentTimeNanos) {
        expected.schedule(new Timer(time));
      }
    }

    NavigableSet<Long> actualTimers = new TreeSet<>();
    NavigableSet<Long> expectedTimers = new TreeSet<>();
    for (int i = 0; i < timerWheel.wheel.length; i++) {
      for (int j = 0; j < timerWheel.wheel[i].length; j++) {
        expectedTimers.addAll(timers(expected.wheel[i][j]));
        actualTimers.addAll(timers(timerWheel.wheel[i][j]));
      }
    }

    System.out.println("Actual\n" + timerWheel);
    System.out.println("Expected\n" + expected);
    System.out.println("Not Expired\n" + actualTimers.headSet(currentTimeNanos, true).stream()
        .map(TimeUnit.NANOSECONDS::toMinutes).collect(toList()));

    assertThat(actualTimers.size(), is(expectedTimers.size()));
  }

  private Set<Long> timers(Node<?, ?> setinel) {
    Set<Long> timers = new HashSet<>();
    for (Node<?, ?> node = setinel.getNextInAccessOrder();
        node != setinel; node = node.getNextInAccessOrder()) {
      timers.add(node.getAccessTime());
    }
    return timers;
  }

  private List<Long> generateTimers() {
    List<Long> timers = new ArrayList<>();
    for (int i = 0; i < TimerWheel.SPANS.length; i++) {
      for (int j = 0; j < (i + 1) * 5_000; j++) {
        timers.add(1 + ThreadLocalRandom.current().nextLong(TimerWheel.SPANS[i] - 1));
      }
    }
    for (int i = 0; i < 500; i++) {
      timers.add(1 + 3 * TimerWheel.SPANS[TimerWheel.SPANS.length - 1]);
    }
    return timers;
  }

  // TODO(ben): Shows cascading effect. Convert into a test case.
  public static void main(String[] args) {
    TimerWheel<Integer, Integer> timerWheel = new TimerWheel<>(timer -> true);

    for (int i = 0; i < TimerWheel.SPANS.length; i++) {
      for (int j = 0; j < (i + 1) * 5000; j++) {
        long expirationTime = 1 + ThreadLocalRandom.current().nextLong(TimerWheel.SPANS[i] - 1);
        timerWheel.schedule(new Timer(expirationTime));
      }
    }
    for (int i = 0; i < 500; i++) {
      timerWheel.schedule(new Timer(1 + 3 * TimerWheel.SPANS[TimerWheel.SPANS.length - 1]));
    }
    System.out.println(timerWheel);

    timerWheel.advance(TimeUnit.HOURS.toNanos(3));
    System.out.println("\nAfter 3 hours:");
    System.out.println(timerWheel);

    timerWheel.advance(TimeUnit.DAYS.toNanos(2));
    System.out.println("\nAfter 2 days:");
    System.out.println(timerWheel);
  }

  static final class Timer implements Node<Integer, Integer> {
    Node<Integer, Integer> prev;
    Node<Integer, Integer> next;
    long accessTime;

    Timer(long accessTime) {
      setAccessTime(accessTime);
    }

    @Override public long getAccessTime() {
      return accessTime;
    }
    @Override public void setAccessTime(long accessTime) {
      this.accessTime = accessTime;
    }
    @Override public Node<Integer, Integer> getPreviousInAccessOrder() {
      return prev;
    }
    @Override public void setPreviousInAccessOrder(@Nullable Node<Integer, Integer> prev) {
      this.prev = prev;
    }
    @Override public Node<Integer, Integer> getNextInAccessOrder() {
      return next;
    }
    @Override public void setNextInAccessOrder(@Nullable Node<Integer, Integer> next) {
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
