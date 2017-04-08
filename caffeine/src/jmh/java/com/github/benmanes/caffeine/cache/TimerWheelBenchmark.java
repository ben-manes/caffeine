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

import javax.annotation.Nullable;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * <pre>{@code
 *   ./gradlew jmh -PincludePattern=TimerWheelBenchmark
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
public class TimerWheelBenchmark {
  private static final int SIZE = (2 << 14);
  private static final int MASK = SIZE - 1;
  private static final long DELTA = TimeUnit.MINUTES.toNanos(5);
  private static final long UPPERBOUND = TimeUnit.DAYS.toNanos(5);

  TimerWheel<Integer, Integer> timerWheel;
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
    timerWheel = new TimerWheel<>(entry -> true);
    for (int i = 0; i < SIZE; i++) {
      times[i] = ThreadLocalRandom.current().nextLong(UPPERBOUND);
    }
  }

  @Benchmark
  public void findBucket(ThreadState threadState) {
    timerWheel.findBucket(times[threadState.index++ & MASK]);
  }

  @Benchmark
  public void schedule(ThreadState threadState) {
    timer.setAccessTime(times[threadState.index++ & MASK]);
    timerWheel.schedule(timer);
  }

  @Benchmark
  public void expire(ThreadState threadState) {
    long accessTime = times[threadState.index++ & MASK];
    timer.setAccessTime(accessTime);
    timerWheel.nanos = accessTime - DELTA;
    timerWheel.schedule(timer);
    timerWheel.advance(accessTime);
  }

  private static final class Timer implements Node<Integer, Integer> {
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
