/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.testing;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Locale.US;
import static org.testng.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Shared utilities for multithreaded tests.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Threads {
  private static final Logger logger = LoggerFactory.getLogger(Threads.class);

  public static final int ITERATIONS = 40_000;
  public static final int NTHREADS = 20;
  public static final int TIMEOUT = 30;

  private Threads() {}

  public static <A> void runTest(A collection, List<BiConsumer<A, Int>> operations) {
    var failures = new ConcurrentLinkedQueue<String>();
    var thrasher = new Thrasher<A>(collection, failures, operations);
    Threads.executeWithTimeOut(failures, () ->
        ConcurrentTestHarness.timeTasks(Threads.NTHREADS, thrasher));
    assertThat(failures).isEmpty();
  }

  public static void executeWithTimeOut(Queue<String> failures, Callable<Long> task) {
    var es = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setDaemon(true).build());
    try {
      var future = es.submit(task);
      long timeNS = future.get(TIMEOUT, TimeUnit.SECONDS);
      logger.debug("\nExecuted in {} second(s)", TimeUnit.NANOSECONDS.toSeconds(timeNS));
    } catch (ExecutionException e) {
      fail("Exception during test: " + e, e);
    } catch (TimeoutException e) {
      handleTimeout(failures, es, e);
    } catch (InterruptedException e) {
      fail("", e);
    } finally {
      es.shutdown();
    }
  }

  public static void handleTimeout(Queue<String> failures, ExecutorService es, TimeoutException e) {
    for (var trace : Thread.getAllStackTraces().values()) {
      for (var element : trace) {
        logger.info("\tat {}", element);
      }
      if (trace.length > 0) {
        logger.info("------");
      }
    }
    MoreExecutors.shutdownAndAwaitTermination(es, 10, TimeUnit.SECONDS);
    for (String failure : failures) {
      logger.debug(failure);
    }
    fail("Spun forever", e);
  }

  public static List<List<Int>> workingSets(int nThreads, int iterations) {
    var keys = IntStream.range(0, iterations)
        .map(i -> ThreadLocalRandom.current().nextInt(iterations / 100))
        .mapToObj(Int::valueOf)
        .collect(toImmutableList());
    return shuffle(nThreads, keys);
  }

  /**
   * Based on the passed in working set, creates N shuffled variants.
   *
   * @param samples the number of variants to create
   * @param baseline the base working set to build from
   */
  private static <T> List<List<T>> shuffle(int samples, List<T> baseline) {
    var workingSets = new ArrayList<List<T>>(samples);
    var workingSet = new ArrayList<T>(baseline);
    for (int i = 0; i < samples; i++) {
      Collections.shuffle(workingSet);
      workingSets.add(List.copyOf(workingSet));
    }
    return List.copyOf(workingSets);
  }

  /** Executes operations against the cache to simulate random load. */
  public static final class Thrasher<A> implements Runnable {
    private final List<BiConsumer<A, Int>> operations;
    private final List<List<Int>> sets;
    private final Queue<String> failures;
    private final AtomicInteger index;
    private final A collection;

    public Thrasher(A collection, Queue<String> failures, List<BiConsumer<A, Int>> operations) {
      this.sets = workingSets(Threads.NTHREADS, Threads.ITERATIONS);
      this.index = new AtomicInteger();
      this.operations = operations;
      this.collection = collection;
      this.failures = failures;
    }

    @Override
    public void run() {
      int id = index.getAndIncrement();
      for (Int e : sets.get(id)) {
        var operation = operations.get(ThreadLocalRandom.current().nextInt(operations.size()));
        try {
          operation.accept(collection, e);
        } catch (Throwable t) {
          failures.add(String.format(US, "Failed: key %s on operation %s%n%s",
              e, operation, Throwables.getStackTraceAsString(t)));
          throw t;
        }
      }
    }
  }
}
