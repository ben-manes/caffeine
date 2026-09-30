/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.base.Throwables;
import com.google.common.collect.ContiguousSet;
import com.google.errorprone.annotations.Var;

/**
 * A fuzzer that exercises maintenance re-entered from a removal listener. The same-thread executor
 * delivers each notification inline, so the listener's operations run a nested maintenance cycle
 * while an eviction or expiration scan is in progress, and that scan must neither corrupt the
 * policy nor fail to end. Entries expire after seconds and the clock advances often, so the scans
 * run with the listener active. An input runs on another thread, so one that never returns fails
 * with that thread's stack instead of stalling the fuzzer.
 */
final class ReentrancyFuzzer {
  private static final Operation[] OPERATIONS = Operation.values();
  private static final Reentry[] REENTRIES = Reentry.values();
  /** The largest key; a small key space keeps the listener's operations on scanned entries. */
  private static final int MAX_KEY = 7;
  /** Every key that an operation may choose. */
  private static final ContiguousSet<Integer> KEYS = ContiguousSet.closed(0, MAX_KEY);
  /** The nesting depth at which the listener stops re-entering the cache. */
  private static final int MAX_DEPTH = 2;
  /** The time allowed for an input, above the validation's own 10 second waits. */
  private static final long TIMEOUT_SECONDS = 30;

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  @FuzzTest(maxDuration = "5m")
  void cache(FuzzedDataProvider data) throws InterruptedException {
    var worker = new AtomicReference<Thread>();
    var future = ConcurrentTestHarness.submit(() -> {
      worker.set(Thread.currentThread());
      fuzz(data);
    });
    try {
      future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwables.throwIfUnchecked(requireNonNull(e.getCause()));
      throw new AssertionError(e);
    } catch (TimeoutException e) {
      var message = new StringBuilder(String.format(US,
          "Operations did not complete within %ds, stopped at:", TIMEOUT_SECONDS));
      for (var frame : requireNonNull(worker.get()).getStackTrace()) {
        message.append("\n\tat ").append(frame);
      }
      throw new AssertionError(message.toString(), e);
    }
  }

  /** Runs fuzzed operations against a cache whose removal listener re-enters it. */
  private static void fuzz(FuzzedDataProvider data) {
    var ticker = new AtomicLong(data.consumeLong());
    long duration = TimeUnit.SECONDS.toNanos(data.consumeInt(1, 60));
    var listener = new ReentrantListener(data);
    var cache = buildCache(data, ticker, duration, listener);

    int operations = data.consumeInt(10, 160);
    for (int i = 0; i < operations; i++) {
      execute(OPERATIONS[data.consumeInt(0, OPERATIONS.length - 1)], data, cache, ticker, duration);
    }

    // Validate without the listener changing the cache under it
    listener.enabled = false;
    var failure = listener.failure;
    if (failure != null) {
      throw new AssertionError("The removal listener's operation failed", failure);
    }
    assertThat(cache).isValid();

    // Every entry has expired after the duration elapses, so none may remain
    ticker.addAndGet(TimeUnit.HOURS.toNanos(1));
    cache.cleanUp();
    assertWithMessage("entries remain after every expiration elapsed")
        .that(cache.estimatedSize()).isEqualTo(0);
  }

  /** Builds a fixed-expiration cache with a fuzzed configuration and the re-entrant listener. */
  private static Cache<Integer, Integer> buildCache(FuzzedDataProvider data, AtomicLong ticker,
      long duration, ReentrantListener listener) {
    var builder = Caffeine.newBuilder().executor(Runnable::run).ticker(ticker::get);

    // Expire after access, after write, or both
    int expiration = data.consumeInt(0, 2);
    if (expiration != 1) {
      builder.expireAfterAccess(Duration.ofNanos(duration));
    }
    if (expiration != 0) {
      builder.expireAfterWrite(Duration.ofNanos(duration));
    }
    if (data.consumeBoolean()) {
      builder.maximumSize(data.consumeInt(2, KEYS.size() + 1));
    }

    Cache<Integer, Integer> cache = builder.removalListener(listener).build();
    listener.cache = cache;
    return cache;
  }

  /** Executes a fuzzed operation against the cache. */
  @SuppressWarnings("CheckReturnValue")
  private static void execute(Operation operation, FuzzedDataProvider data,
      Cache<Integer, Integer> cache, AtomicLong ticker, long duration) {
    switch (operation) {
      case PUT: {
        cache.put(data.consumeInt(0, MAX_KEY), data.consumeInt(0, 2));
        break;
      }
      case GET: {
        cache.getIfPresent(data.consumeInt(0, MAX_KEY));
        break;
      }
      case INVALIDATE: {
        cache.invalidate(data.consumeInt(0, MAX_KEY));
        break;
      }
      case CLEAN_UP: {
        cache.cleanUp();
        break;
      }
      case ADVANCE: {
        ticker.addAndGet(data.consumeLong(0, duration));
        break;
      }
      case TICK: {
        ticker.addAndGet(data.consumeLong(0, TimeUnit.SECONDS.toNanos(2)));
        break;
      }
    }
  }

  /** Performs a fuzzed operation from within a removal notification. */
  @SuppressWarnings("CheckReturnValue")
  private static void reenter(Reentry reentry, FuzzedDataProvider data,
      Cache<Integer, Integer> cache) {
    switch (reentry) {
      case GET: {
        cache.getIfPresent(data.consumeInt(0, MAX_KEY));
        break;
      }
      case PUT: {
        cache.put(data.consumeInt(0, MAX_KEY), data.consumeInt(0, 2));
        break;
      }
      case INVALIDATE: {
        cache.invalidate(data.consumeInt(0, MAX_KEY));
        break;
      }
      case CLEAN_UP: {
        cache.cleanUp();
        break;
      }
      case GET_ALL_PRESENT: {
        cache.getAllPresent(KEYS);
        break;
      }
      case PUT_ALL: {
        // Rewrites every present entry with its own value, renewing it without a replacement
        cache.putAll(cache.getAllPresent(KEYS));
        break;
      }
      case SET_MAXIMUM: {
        long maximum = data.consumeLong(1, KEYS.size());
        cache.policy().eviction().ifPresent(eviction -> eviction.setMaximum(maximum));
        break;
      }
    }
  }

  /**
   * A removal listener that re-enters the cache with fuzzed operations, up to a nesting depth. The
   * cache logs and swallows a listener's failure, so the listener keeps the first for the verdict.
   */
  private static final class ReentrantListener implements RemovalListener<Integer, Integer> {
    private final FuzzedDataProvider data;

    @Var @Nullable Cache<Integer, Integer> cache;
    @Var @Nullable Throwable failure;
    @Var boolean enabled = true;
    @Var private int depth;

    ReentrantListener(FuzzedDataProvider data) {
      this.data = data;
    }

    @Override
    public void onRemoval(@Nullable Integer key, @Nullable Integer value, RemovalCause cause) {
      var target = cache;
      if (!enabled || (target == null) || (depth == MAX_DEPTH)) {
        return;
      }
      depth++;
      try {
        int operations = data.consumeInt(0, 3);
        for (int i = 0; i < operations; i++) {
          reenter(REENTRIES[data.consumeInt(0, REENTRIES.length - 1)], data, target);
        }
      } catch (RuntimeException | Error e) {
        if (failure == null) {
          failure = e;
        }
      } finally {
        depth--;
      }
    }
  }

  private enum Operation { PUT, GET, INVALIDATE, CLEAN_UP, ADVANCE, TICK }

  private enum Reentry { GET, PUT, INVALIDATE, CLEAN_UP, GET_ALL_PRESENT, PUT_ALL, SET_MAXIMUM }
}
