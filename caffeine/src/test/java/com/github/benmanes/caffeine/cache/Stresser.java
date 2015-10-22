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

import java.time.LocalTime;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * A stress test to observe if the cache has a memory leak by not being able to drain the buffers
 * fast enough.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Stresser {
  private static final String[] STATUS = { "Idle", "Required", "Processing" };
  private static final int THREADS = 2 * Runtime.getRuntime().availableProcessors();
  private static final long STATUS_INTERVAL = 5;
  private static final int MAX_SIZE = (1 << 12);
  private static final int LENGTH = (1 << 20);
  private static final int MASK = LENGTH - 1;

  private final BoundedLocalCache<Integer, Integer> local;
  private final ScheduledExecutorService statusExecutor;
  private final Cache<Integer, Integer> cache;
  private final LongAdder evictions;
  private final Integer[] ints;

  private final boolean reads = false;

  public Stresser() {
    ThreadFactory threadFactory = new ThreadFactoryBuilder()
        .setPriority(Thread.MAX_PRIORITY)
        .setDaemon(true)
        .build();
    statusExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    statusExecutor.scheduleAtFixedRate(newStatusTask(), THREADS, STATUS_INTERVAL, SECONDS);
    evictions = new LongAdder();
    cache = Caffeine.newBuilder()
        .removalListener((k, v, c) -> evictions.increment())
        .maximumSize(reads ? LENGTH : MAX_SIZE)
        .initialCapacity(MAX_SIZE)
        .build();
    local = (BoundedLocalCache<Integer, Integer>) cache.asMap();

    ints = new Integer[LENGTH];
    Arrays.setAll(ints, i-> {
      Integer key = i;
      cache.put(key, key);
      return key;
    });
  }

  public void run() throws InterruptedException {
    ConcurrentTestHarness.timeTasks(THREADS, () -> {
      int index = ThreadLocalRandom.current().nextInt();
      for (;;) {
        Integer key = ints[index++ & MASK];
        if (reads) {
          cache.getIfPresent(ints[index++ & MASK]);
        } else {
          cache.put(key, key);
          Thread.yield();
        }
      }
    });
  }

  private Runnable newStatusTask() {
    return new Runnable() {
      long runningTime;

      @Override
      public void run() {
        local.evictionLock.lock();
        int pendingWrites = local.writeQueue().size();
        local.evictionLock.unlock();

        runningTime += STATUS_INTERVAL;
        String elapsedTime = LocalTime.ofSecondOfDay(runningTime).toString();
        System.out.printf("---------- %s ----------%n", elapsedTime);
        System.out.printf("Pending reads = %,d%n", local.readBuffer.size());
        System.out.printf("Pending write = %,d%n", pendingWrites);
        System.out.printf("Drain status = %s%n", STATUS[local.drainStatus]);
        System.out.printf("Evictions = %,d%n", evictions.intValue());
        System.out.printf("Size = %,d%n", local.data.mappingCount());
        System.out.printf("Lock = [%s%n", StringUtils.substringAfter(
            local.evictionLock.toString(), "["));
      }
    };
  }

  public static void main(String[] args) throws Exception {
    new Stresser().run();
  }
}
