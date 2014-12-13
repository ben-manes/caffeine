/*
 * Copyright 2013 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.profile;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;
import java.util.concurrent.atomic.LongAdder;

import com.github.benmanes.caffeine.ConcurrentTestHarness;
import com.github.benmanes.caffeine.EliminationStack;
import com.google.common.base.Stopwatch;

/**
 * A hook for inspecting with an attached profiler.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
public final class EliminationProfile {
  static final int DISPLAY_DELAY_SEC = 5;
  static final Integer ELEMENT = 1;

  final LongAdder calls;
  final Runnable runner;

  EliminationProfile() {
    calls = new LongAdder();

    // The task to profile
    runner = newEliminationStackRunner();
    //runner = newLinkedTransferQueueRunner();
  }

  void run() throws Exception {
    ConcurrentTestHarness.timeTasks(25, runner);
  }

  Runnable newEliminationStackRunner() {
    final EliminationStack<Integer> stack = new EliminationStack<>();
    return () -> {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      for (;;) {
        if (random.nextBoolean()) {
          stack.push(ELEMENT);
        } else {
          stack.pop();
        }
        calls.increment();
      }
    };
  }

  Runnable newLinkedTransferQueueRunner() {
    TransferQueue<Integer> queue = new LinkedTransferQueue<>();
    return () -> {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      for (;;) {
        if (random.nextBoolean()) {
          queue.offer(ELEMENT);
        } else {
          queue.poll();
        }
        calls.increment();
      }
    };
  }

  void scheduleStatusTask() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
      long count = calls.longValue();
      long rate = count / stopwatch.elapsed(TimeUnit.SECONDS);
      System.out.printf("%s - %,d [%,d / sec]%n", stopwatch, count, rate);
    }, DISPLAY_DELAY_SEC, DISPLAY_DELAY_SEC, TimeUnit.SECONDS);
  }

  public static void main(String[] args) throws Exception {
    EliminationProfile profile = new EliminationProfile();
    profile.scheduleStatusTask();
    profile.run();
  }
}
