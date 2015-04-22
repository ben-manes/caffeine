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
package com.github.benmanes.caffeine;

import java.util.Queue;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * A concurrent benchmark where multiple threads produce into, and a single thread consumes from,
 * a shared queue.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Group)
public class SingleConsumerQueueBenchmark {
  @Param({"SingleConsumerQueue_optimistic",
    "SingleConsumerQueue_linearizable",
    "ConcurrentLinkedQueue"})
  QueueType queueType;

  Queue<Boolean> queue;

  @Setup
  public void setup() {
    queue = queueType.create();
  }

  @Benchmark  @Group("no_contention") @GroupThreads(1)
  public void no_contention_offer() {
    queue.offer(Boolean.TRUE);
  }

  @Benchmark @Group("no_contention") @GroupThreads(1)
  public void no_contention_poll() {
    queue.poll();
  }

  @Benchmark @Group("mild_contention") @GroupThreads(2)
  public void mild_contention_offer() {
    queue.offer(Boolean.TRUE);
  }

  @Benchmark @Group("mild_contention") @GroupThreads(1)
  public void mild_contention_poll() {
    queue.poll();
  }

  @Benchmark @Group("high_contention") @GroupThreads(8)
  public void high_contention_offer() {
    queue.offer(Boolean.TRUE);
  }

  @Benchmark @Group("high_contention") @GroupThreads(1)
  public void high_contention_poll() {
    queue.poll();
  }
}
