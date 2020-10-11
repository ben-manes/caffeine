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
package com.github.benmanes.caffeine.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;

/**
 * A testing harness for concurrency related executions.
 * <p/>
 * This harness will ensure that all threads execute at the same time, records
 * the full execution time, and optionally retrieves the responses from each
 * thread. This harness can be used for performance tests, investigations of
 * lock contention, etc.
 * <p/>
 * This code was adapted from <tt>Java Concurrency in Practice</tt>, using an
 * example of a {@link CountDownLatch} for starting and stopping threads in
 * timing tests.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ConcurrentTestHarness {
  public static final ThreadFactory DAEMON_FACTORY = new ThreadFactoryBuilder()
      .setPriority(Thread.MIN_PRIORITY).setDaemon(true).build();
  public static final ScheduledExecutorService scheduledExecutor =
      Executors.newSingleThreadScheduledExecutor(DAEMON_FACTORY);
  public static final ExecutorService executor = Executors.newCachedThreadPool(DAEMON_FACTORY);

  private ConcurrentTestHarness() {}

  /** Executes the task using the shared thread pool. */
  public static void execute(Runnable task) {
    executor.execute(task);
  }

  /**
   * Executes a task, on N threads, all starting at the same time.
   *
   * @param nThreads the number of threads to execute
   * @param task the task to execute in each thread
   * @return the execution time for all threads to complete, in nanoseconds
   */
  public static long timeTasks(int nThreads, Runnable task) {
    return timeTasks(nThreads, Executors.callable(task)).executionTime();
  }

  /**
   * Executes a task, on N threads, all starting at the same time.
   *
   * @param nThreads the number of threads to execute
   * @param task the task to execute in each thread
   * @return the result of each task and the full execution time, in nanoseconds
   */
  public static <T> TestResult<T> timeTasks(int nThreads, Callable<T> task) {
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch endGate = new CountDownLatch(nThreads);
    AtomicReferenceArray<T> results = new AtomicReferenceArray<T>(nThreads);

    for (int i = 0; i < nThreads; i++) {
      final int index = i;
      executor.execute(() -> {
        try {
          startGate.await();
          try {
            results.set(index, task.call());
          } finally {
            endGate.countDown();
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    }

    long start = System.nanoTime();
    startGate.countDown();
    Uninterruptibles.awaitUninterruptibly(endGate);
    long end = System.nanoTime();
    return new TestResult<T>(end - start, toList(results));
  }

  /**
   * Migrates the data from the atomic array to a {@link List} for easier
   * consumption.
   *
   * @param data the per-thread results from the test
   * @return the per-thread results as a standard collection
   */
  private static <T> List<T> toList(AtomicReferenceArray<T> data) {
    List<T> list = new ArrayList<>(data.length());
    for (int i = 0; i < data.length(); i++) {
      list.add(data.get(i));
    }
    return list;
  }

  /**
   * The results of the test harness's execution.
   *
   * @param <T> the data type produced by the task
   */
  public static final class TestResult<T> {
    private final long executionTime;
    private final List<T> results;

    public TestResult(long executionTime, List<T> results) {
      this.executionTime = executionTime;
      this.results = results;
    }

    /**
     * The test's execution time, in nanoseconds.
     *
     * @return The time to complete the test.
     */
    public long executionTime() {
      return executionTime;
    }

    /**
     * The results from executing the tasks.
     *
     * @return The outputs from the tasks.
     */
    public List<T> results() {
      return results;
    }
  }
}
