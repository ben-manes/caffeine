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
package com.github.benmanes.caffeine.xfer;

import java.util.Queue;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * A concurrent benchmark where threads transfer elements through a shared queue.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Group)
public abstract class AbstractTransferBenchmark {

  /** Returns the queue to benchmark. */
  protected abstract Queue<Boolean> queue();

  @Benchmark
  @GroupThreads(1)
  @Group("no_contention")
  public void no_contention_push() {
    queue().offer(Boolean.TRUE);
  }

  @Benchmark
  @GroupThreads(1)
  @Group("no_contention")
  public void no_contention_pop() {
    queue().poll();
  }

  @Benchmark
  @GroupThreads(4)
  @Group("mild_contention")
  public void mild_contention_push() {
    queue().offer(Boolean.TRUE);
  }

  @Benchmark
  @GroupThreads(4)
  @Group("mild_contention")
  public void mild_contention_pop() {
    queue().poll();
  }

  @Benchmark
  @GroupThreads(8)
  @Group("high_contention")
  public void high_contention_push() {
    queue().offer(Boolean.TRUE);
  }

  @Benchmark
  @GroupThreads(8)
  @Group("high_contention")
  public void high_contention_pop() {
    queue().poll();
  }
}
