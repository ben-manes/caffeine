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
package com.github.benmanes.caffeine.cache.testing;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.util.concurrent.ForwardingExecutorService;

/**
 * An executor that retains metrics regarding the execution history.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TrackingExecutor extends ForwardingExecutorService {
  private final ExecutorService delegate;
  private final AtomicInteger failures;

  public TrackingExecutor(ExecutorService executor) {
    delegate = requireNonNull(executor);
    failures = new AtomicInteger();
  }

  @Override
  public void execute(Runnable command) {
    try {
      delegate.execute(() -> {
        try {
          command.run();
        } catch (Throwable t) {
          failures.incrementAndGet();
          throw t;
        }
      });
    } catch (Throwable t) {
      failures.incrementAndGet();
      throw t;
    }
  }

  public int failureCount() {
    return failures.get();
  }

  @Override
  public ExecutorService delegate() {
    return delegate;
  }
}
