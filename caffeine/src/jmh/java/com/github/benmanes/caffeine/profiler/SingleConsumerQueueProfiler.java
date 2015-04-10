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
package com.github.benmanes.caffeine.profiler;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.benmanes.caffeine.QueueType;

/**
 * @author Ben Manes (ben.manes@gmail.com)
 */
public final class SingleConsumerQueueProfiler extends ProfilerHook {
  final QueueType queueType = QueueType.SingleConsumerQueue_optimistic;
  final AtomicBoolean consumer = new AtomicBoolean();
  final Queue<Boolean> queue = queueType.create();

  SingleConsumerQueueProfiler() {
    ProfilerHook.NUM_THREADS = 8;
  }

  @Override
  protected void profile() {
    if (consumer.compareAndSet(false, true)) {
      for (;;) {
        queue.poll();
      }
    } else {
      for (;;) {
        queue.offer(Boolean.TRUE);
        calls.increment();
      }
    }
  }

  public static void main(String[] args) {
    ProfilerHook profile = new SingleConsumerQueueProfiler();
    profile.run();
  }
}
