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

import java.lang.ref.ReferenceQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import com.github.benmanes.caffeine.testing.Int;

/**
 * {@snippet lang="shell" :
 * ./gradlew jmh -PincludePattern=TimerWheelBenchmark --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
@SuppressWarnings("ClassEscapesDefinedScope")
public class TimerWheelBenchmark {
  private static final int SIZE = (2 << 14);
  private static final int MASK = SIZE - 1;
  private static final long DELTA = TimeUnit.MINUTES.toNanos(5);
  private static final long UPPERBOUND = TimeUnit.DAYS.toNanos(5);

  TimerWheel<Int, Int> timerWheel;
  MockCache cache;
  long[] times;
  Timer timer;

  @State(Scope.Thread)
  public static class ThreadState {
    int index;
  }

  @Setup
  public void setup() {
    timer = new Timer(0);
    times = new long[SIZE];
    cache = new MockCache();
    timerWheel = new TimerWheel<>();
    for (int i = 0; i < SIZE; i++) {
      times[i] = ThreadLocalRandom.current().nextLong(UPPERBOUND);
      timerWheel.schedule(new Timer(times[i]));
    }
    timerWheel.schedule(timer);
  }

  @Benchmark
  public Node<Int, Int> findBucket(ThreadState threadState) {
    return timerWheel.findBucket(times[threadState.index++ & MASK]);
  }

  @Benchmark
  public void reschedule(ThreadState threadState) {
    timer.setVariableTime(times[threadState.index++ & MASK]);
    timerWheel.reschedule(timer);
  }

  @Benchmark
  public void expire(ThreadState threadState) {
    long time = times[threadState.index++ & MASK];
    timer.setVariableTime(time);
    timerWheel.nanos = (time - DELTA);
    timerWheel.deschedule(timer);
    timerWheel.schedule(timer);
    timerWheel.advance(cache, time);
  }

  @Benchmark
  public long getExpirationDelay() {
    return timerWheel.getExpirationDelay();
  }

  @Benchmark
  @SuppressWarnings({"ForEachIterable", "PMD.ForLoopCanBeForeach"})
  public int ascending() {
    int count = 0;
    for (var i = timerWheel.iterator(); i.hasNext();) {
      i.next();
      count++;
    }
    return count;
  }

  @Benchmark
  public int descending() {
    int count = 0;
    for (var i = timerWheel.descendingIterator(); i.hasNext();) {
      i.next();
      count++;
    }
    return count;
  }

  static final class Timer extends Node<Int, Int> {
    @Nullable Node<Int, Int> prev;
    @Nullable Node<Int, Int> next;
    long time;

    Timer(long time) {
      setVariableTime(time);
    }

    @Override public long getVariableTime() {
      return time;
    }
    @Override public void setVariableTime(long time) {
      this.time = time;
    }
    @SuppressWarnings("NullAway")
    @Override public Node<Int, Int> getPreviousInVariableOrder() {
      return prev;
    }
    @Override public void setPreviousInVariableOrder(@Nullable Node<Int, Int> prev) {
      this.prev = prev;
    }
    @SuppressWarnings("NullAway")
    @Override public Node<Int, Int> getNextInVariableOrder() {
      return next;
    }
    @Override public void setNextInVariableOrder(@Nullable Node<Int, Int> next) {
      this.next = next;
    }

    @Override public Int getKey() { throw new UnsupportedOperationException(); }
    @Override public Object getKeyReference() { throw new UnsupportedOperationException(); }
    @Override public Int getValue() { throw new UnsupportedOperationException(); }
    @Override public Object getValueReference() { throw new UnsupportedOperationException(); }
    @Override public void setValue(Int value, @Nullable ReferenceQueue<Int> queue) {}
    @Override public boolean containsValue(Object value) { return false; }
    @Override public boolean isAlive() { return false; }
    @Override public boolean isRetired() { return false; }
    @Override public boolean isDead() { return false; }
    @Override public void retire() {}
    @Override public void die() {}
  }

  static final class MockCache extends BoundedLocalCache<Int, Int> {

    @SuppressWarnings({"rawtypes", "unchecked"})
    MockCache() {
      super((Caffeine) Caffeine.newBuilder(), /* cacheLoader= */ null, /* isAsync= */ false);
    }

    @Override
    boolean evictEntry(Node<Int, Int> node, RemovalCause cause, long now) {
      return true;
    }
  }
}
