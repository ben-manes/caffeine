/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.profiler;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.base.Stopwatch;

/**
 * A skeletal hook for inspecting with an attached profiler.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
public abstract class ProfilerHook {
  static int DISPLAY_DELAY_SEC = 5;
  static int NUM_THREADS = 8;

  protected final LongAdder calls;

  ProfilerHook() {
    calls = new LongAdder();
  }

  public final void run() {
    scheduleStatusTask();
    ConcurrentTestHarness.timeTasks(NUM_THREADS, this::profile);
  }

  protected abstract void profile();

  @SuppressWarnings("FutureReturnValueIgnored")
  private void scheduleStatusTask() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
      long count = calls.longValue();
      long rate = count / stopwatch.elapsed(TimeUnit.SECONDS);
      System.out.printf("%s - %,d [%,d / sec]%n", stopwatch, count, rate);
    }, DISPLAY_DELAY_SEC, DISPLAY_DELAY_SEC, TimeUnit.SECONDS);
  }
}
