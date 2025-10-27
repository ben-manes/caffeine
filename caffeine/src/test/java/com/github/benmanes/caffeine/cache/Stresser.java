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
package com.github.benmanes.caffeine.cache;

import static java.util.Locale.US;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.errorprone.annotations.Var;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Option;

/**
 * A stress test to observe if the cache is able to drain the buffers fast enough under a synthetic
 * load.
 * <p>
 * {@snippet lang="shell" :
 * ./gradlew :caffeine:stress --workload=[read, write, refresh]
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("SystemOut")
@Command(mixinStandardHelpOptions = true)
public final class Stresser implements Runnable {
  private static final String[] STATUS =
    { "Idle", "Required", "Processing -> Idle", "Processing -> Required" };
  private static final int MAX_THREADS = 2 * Runtime.getRuntime().availableProcessors();
  private static final int WRITE_MAX_SIZE = (1 << 12); // 4,096
  private static final int TOTAL_KEYS = (1 << 20); // 1,048,576
  private static final int MASK = TOTAL_KEYS - 1;
  private static final int STATUS_INTERVAL = 5;

  @Option(names = "--workload", required = true,
      description = "The workload type: ${COMPLETION-CANDIDATES}")
  private Workload workload;
  @Option(names = "--duration", required = false, description = "The run duration (e.g. PT30S)")
  private Duration duration;

  private BoundedLocalCache<Integer, Integer> local;
  private LoadingCache<Integer, Integer> cache;
  private ScheduledExecutorService scheduler;
  private Stopwatch stopwatch;
  private Integer[] ints;

  @Override
  public void run() {
    try {
      initialize();
      execute();
    } finally {
      scheduler.shutdown();
    }
  }

  @SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored", "PMD.DoNotTerminateVM"})
  private void initialize() {
    var threadFactory = new ThreadFactoryBuilder()
        .setPriority(Thread.MAX_PRIORITY)
        .setDaemon(true)
        .build();
    scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
    scheduler.scheduleAtFixedRate(this::status, STATUS_INTERVAL, STATUS_INTERVAL, SECONDS);
    if (duration != null) {
      System.out.printf(US, "Executing for %s%n%n", duration);
      scheduler.schedule(() -> {
        System.out.println("Done");
        System.exit(0);
      }, duration.toNanos(), TimeUnit.NANOSECONDS);
    }
    cache = Caffeine.newBuilder()
        .maximumSize(workload.maxEntries)
        .recordStats()
        .build(key -> key);
    local = (BoundedLocalCache<Integer, Integer>) cache.asMap();
    ints = new Integer[TOTAL_KEYS];
    Arrays.setAll(ints, key -> {
      cache.put(key, key);
      return key;
    });
    cache.cleanUp();
    local.refreshes();
    stopwatch = Stopwatch.createStarted();
    status();
  }

  @SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored"})
  private void execute() {
    ConcurrentTestHarness.timeTasks(workload.maxThreads, () -> {
      @Var int index = ThreadLocalRandom.current().nextInt();
      for (;;) {
        Integer key = ints[index++ & MASK];
        switch (workload) {
          case READ:
            cache.getIfPresent(key);
            break;
          case WRITE:
            cache.put(key, key);
            break;
          case REFRESH:
            cache.refresh(key);
            break;
        }
      }
    });
  }

  private void status() {
    var evictionLock = local.evictionLock;
    int pendingWrites;
    int drainStatus;

    evictionLock.lock();
    try {
      pendingWrites = local.writeBuffer.size();
      drainStatus = local.drainStatusAcquire();
    } finally {
      evictionLock.unlock();
    }

    var elapsedTime = LocalTime.ofSecondOfDay(stopwatch.elapsed(SECONDS));
    System.out.printf(US, "---------- %s ----------%n", elapsedTime);
    System.out.printf(US, "Pending reads: %,d; writes: %,d%n",
        local.readBuffer.size(), pendingWrites);
    System.out.printf(US, "Drain status = %s (%s)%n", STATUS[drainStatus], drainStatus);
    System.out.printf(US, "Evictions = %,d%n", cache.stats().evictionCount());
    System.out.printf(US, "Size = %,d (max: %,d)%n",
        local.data.mappingCount(), workload.maxEntries);
    System.out.printf(US, "Lock = [%s%n", StringUtils.substringAfter(evictionLock.toString(), "["));
    System.out.printf(US, "Pending reloads = %,d%n", local.refreshes().size());
    System.out.printf(US, "Pending tasks = %,d%n",
        ForkJoinPool.commonPool().getQueuedSubmissionCount());

    long maxMemory = Runtime.getRuntime().maxMemory();
    long freeMemory = Runtime.getRuntime().freeMemory();
    long allocatedMemory = Runtime.getRuntime().totalMemory();
    System.out.printf(US, "Max Memory = %,d bytes%n", maxMemory);
    System.out.printf(US, "Free Memory = %,d bytes%n", freeMemory);
    System.out.printf(US, "Allocated Memory = %,d bytes%n", allocatedMemory);

    System.out.println();
  }

  public static void main(String[] args) {
    new CommandLine(Stresser.class)
        .setCommandName(Stresser.class.getSimpleName())
        .setColorScheme(Help.defaultColorScheme(Help.Ansi.ON))
        .setCaseInsensitiveEnumValuesAllowed(true)
        .execute(args);
  }

  private enum Workload {
    READ(MAX_THREADS, TOTAL_KEYS),
    WRITE(MAX_THREADS, WRITE_MAX_SIZE),
    REFRESH(MAX_THREADS, TOTAL_KEYS / 4);

    final int maxThreads;
    final int maxEntries;

    Workload(int maxThreads, int maxEntries) {
      this.maxThreads = maxThreads;
      this.maxEntries = maxEntries;
    }
    @Override public String toString() {
      return name().toLowerCase(US);
    }
  }
}
