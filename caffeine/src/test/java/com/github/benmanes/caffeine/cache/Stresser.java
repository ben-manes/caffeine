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

import java.time.LocalTime;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Option;

/**
 * A stress test to observe if the cache is able to drain the buffers fast enough under a synthetic
 * load.
 * <p>
 * <pre>{@code
 *   ./gradlew :caffeine:stress --workload=[read, write, refresh]
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
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

  private BoundedLocalCache<Integer, Integer> local;
  private LoadingCache<Integer, Integer> cache;
  private Stopwatch stopwatch;
  private Integer[] ints;

  @Override
  public void run() {
    initialize();
    execute();
  }

  @SuppressWarnings({"CheckReturnValue", "FutureReturnValueIgnored"})
  private void initialize() {
    var threadFactory = new ThreadFactoryBuilder()
        .setPriority(Thread.MAX_PRIORITY)
        .setDaemon(true)
        .build();
    Executors.newSingleThreadScheduledExecutor(threadFactory)
        .scheduleAtFixedRate(this::status, STATUS_INTERVAL, STATUS_INTERVAL, SECONDS);
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
      int index = ThreadLocalRandom.current().nextInt();
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
    int drainStatus;
    int pendingWrites;
    local.evictionLock.lock();
    try {
      pendingWrites = local.writeBuffer.size();
      drainStatus = local.drainStatusAcquire();
    } finally {
      local.evictionLock.unlock();
    }

    var elapsedTime = LocalTime.ofSecondOfDay(stopwatch.elapsed(SECONDS));
    System.out.printf(US, "---------- %s ----------%n", elapsedTime);
    System.out.printf(US, "Pending reads: %,d; writes: %,d%n",
        local.readBuffer.size(), pendingWrites);
    System.out.printf(US, "Drain status = %s (%s)%n", STATUS[drainStatus], drainStatus);
    System.out.printf(US, "Evictions = %,d%n", cache.stats().evictionCount());
    System.out.printf(US, "Size = %,d (max: %,d)%n",
        local.data.mappingCount(), workload.maxEntries);
    System.out.printf(US, "Lock = [%s%n", StringUtils.substringAfter(
        local.evictionLock.toString(), "["));
    System.out.printf(US, "Pending reloads = %,d%n", local.refreshes.size());
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

    private final int maxThreads;
    private final int maxEntries;

    Workload(int maxThreads, int maxEntries) {
      this.maxThreads = maxThreads;
      this.maxEntries = maxEntries;
    }
    @Override public String toString() {
      return name().toLowerCase(US);
    }
  }
}
