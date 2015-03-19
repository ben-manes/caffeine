/*
 * Copyright 2011 Google Inc. All Rights Reserved.
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

import static java.util.concurrent.TimeUnit.SECONDS;

import java.text.NumberFormat;
import java.time.LocalTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

import com.github.benmanes.caffeine.ConcurrentTestHarness;
import com.github.benmanes.caffeine.cache.simulator.generator.IntegerGenerator;
import com.github.benmanes.caffeine.cache.simulator.generator.ScrambledZipfianGenerator;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * A stress test to observe if the cache has a memory leak by not being able to drain the buffers
 * fast enough.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Stresser {
  private static final long STATUS_INTERVAL = 5;
  private static final int SIZE = (2 << 14);
  private static final int MASK = SIZE - 1;
  private static final int THREADS = 8;

  private Integer[] ints;
  private Cache<Integer, Boolean> cache;
  private BoundedLocalCache<Integer, Boolean> local;
  private ScheduledExecutorService statusExecutor;
  private LongAdder evictions;

  public Stresser() {
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setPriority(Thread.MAX_PRIORITY)
        .setDaemon(true)
        .build();
    statusExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    statusExecutor.scheduleAtFixedRate(newStatusTask(), THREADS, STATUS_INTERVAL, SECONDS);
    cache = Caffeine.newBuilder()
        .removalListener(notif -> evictions.increment())
        .executor(MoreExecutors.directExecutor())
        .maximumSize(SIZE)
        .build();
    evictions = new LongAdder();
    local = (BoundedLocalCache<Integer, Boolean>) cache.asMap();
  }

  public void run() throws InterruptedException {
    for (int i = 0; i < SIZE; i++) {
      cache.put(i, Boolean.TRUE);
    }
    ints = new Integer[SIZE];
    IntegerGenerator generator = new ScrambledZipfianGenerator(SIZE);
    for (int i = 0; i < SIZE; i++) {
      ints[i] = generator.nextInt();
    }

    ConcurrentTestHarness.timeTasks(THREADS, () -> {
      int index = ThreadLocalRandom.current().nextInt();
      for (;;) {
        cache.put(ints[index++ & MASK], Boolean.FALSE);
        cache.getIfPresent(ints[index++ & MASK]);
        Thread.yield();
      }
    });
  }

  private Runnable newStatusTask() {
    return new Runnable() {
      long runningTime;

      @Override
      public void run() {
        runningTime += STATUS_INTERVAL;
        String elapsedTime = LocalTime.ofSecondOfDay(runningTime).toString();
        String pendingReads = NumberFormat.getInstance().format(local.readBuffer.size());
        String pendingWrites = NumberFormat.getInstance().format(local.writeQueue().size());
        System.out.printf("---------- %s ----------%n", elapsedTime);
        System.out.printf("Pending reads = %s%n", pendingReads);
        System.out.printf("Pending write = %s%n", pendingWrites);
        System.out.printf("Drain status = %s%n", local.drainStatus.get());
        System.out.printf("Lock status = %s%n", local.evictionLock.isLocked());
        System.out.printf("Evictions = %,d%n", evictions.intValue());
      }
    };
  }

  public static void main(String[] args) throws Exception {
    new Stresser().run();
  }
}
