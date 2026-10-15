/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.examples.virtualthreads;

import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying Caffeine caching behavior when powered by Java 21 Virtual Threads.
 *
 * @author ronitgupta138 (Ronit Gupta)
 */
final class VirtualThreadCacheExampleTest {

  @Test
  void virtualThreadExecutionOnAsyncLoad() {
    var wasVirtual = new AtomicBoolean();
    var executedThreadName = new AtomicReference<String>();

    try (var example = new VirtualThreadCacheExample<String, String>(key -> {
      wasVirtual.set(Thread.currentThread().isVirtual());
      executedThreadName.set(Thread.currentThread().getName());
      return "value-" + key;
    })) {
      var future = example.get("alpha");
      var result = future.join();

      assertThat(result).isEqualTo("value-alpha");
      assertThat(wasVirtual.get()).isTrue();
      assertThat(executedThreadName.get()).isNotNull();
    }
  }

  @Test
  void virtualThreadExecutionOnAsyncReload() {
    var reloadWasVirtual = new AtomicBoolean();
    var reloadCount = new AtomicInteger();

    try (var example = new VirtualThreadCacheExample<String, String>(
        key -> "initial-" + key,
        (key, oldValue) -> {
          reloadWasVirtual.set(Thread.currentThread().isVirtual());
          reloadCount.incrementAndGet();
          return "refreshed-" + key + "-from-" + oldValue;
        },
        null)) {

      // Initial load
      assertThat(example.get("item1").join()).isEqualTo("initial-item1");

      // Asynchronous reload
      var reloadFuture = example.refresh("item1");
      var refreshed = reloadFuture.join();

      assertThat(refreshed).isEqualTo("refreshed-item1-from-initial-item1");
      assertThat(reloadWasVirtual.get()).isTrue();
      assertThat(reloadCount.get()).isEqualTo(1);
    }
  }

  @Test
  void nonBlockingCacheLookups() {
    var loadStarted = new CountDownLatch(1);
    var proceedWithLoad = new CountDownLatch(1);

    try (var example = new VirtualThreadCacheExample<String, String>(key -> {
      loadStarted.countDown();
      try {
        proceedWithLoad.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      return "loaded-" + key;
    })) {

      long startTime = System.nanoTime();
      var future = example.get("delayedKey");
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

      // get() must return immediately with an incomplete CompletableFuture
      assertThat(elapsedMillis).isLessThan(100);
      assertThat(future.isDone()).isFalse();

      // Complete the background load
      proceedWithLoad.countDown();
      assertThat(future.join()).isEqualTo("loaded-delayedKey");
    }
  }

  @Test
  void concurrentLoadingWithoutThreadExhaustion() {
    int concurrentTasks = 1_000;
    var virtualExecutions = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    var nonVirtualExecutions = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    try (var example = new VirtualThreadCacheExample<Integer, String>(key -> {
      if (Thread.currentThread().isVirtual()) {
        virtualExecutions.add(Thread.currentThread().threadId());
      } else {
        nonVirtualExecutions.add(Thread.currentThread().threadId());
      }
      try {
        // Simulate blocking network / database I/O
        Thread.sleep(Duration.ofMillis(50));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      return "result-" + key;
    })) {

      long start = System.nanoTime();
      var futures = new ArrayList<CompletableFuture<String>>(concurrentTasks);

      for (int i = 0; i < concurrentTasks; i++) {
        futures.add(example.get(i));
      }

      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
      long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      // 1000 tasks each sleeping 50ms on platform threads (e.g. pool size 4-8) would take 6-12+ seconds.
      // With virtual threads, all 1000 run concurrently and complete rapidly.
      assertThat(durationMillis).isLessThan(4_000);
      assertThat(nonVirtualExecutions).isEmpty();
      assertThat(virtualExecutions).isNotEmpty();

      for (int i = 0; i < concurrentTasks; i++) {
        assertThat(futures.get(i).join()).isEqualTo("result-" + i);
      }
    }
  }

  @Test
  void staleWhileRevalidateDuringAsyncReload() throws Exception {
    var reloadEntered = new CountDownLatch(1);
    var proceedReload = new CountDownLatch(1);

    try (var example = new VirtualThreadCacheExample<String, String>(
        key -> "v1",
        (key, oldValue) -> {
          reloadEntered.countDown();
          try {
            proceedReload.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          return "v2";
        },
        null)) {

      // Populate cache with v1
      assertThat(example.get("key").join()).isEqualTo("v1");

      // Trigger asynchronous reload
      var reloadFuture = example.refresh("key");
      assertThat(reloadEntered.await(5, TimeUnit.SECONDS)).isTrue();

      // While reload is blocked on I/O in the background virtual thread, existing value is served
      assertThat(example.synchronous().get("key")).isEqualTo("v1");

      // Release reload
      proceedReload.countDown();
      assertThat(reloadFuture.get(5, TimeUnit.SECONDS)).isEqualTo("v2");

      // Once the reload future completes, the background callback commits the new value
      for (int i = 0; i < 50; i++) {
        if ("v2".equals(example.synchronous().get("key"))) {
          break;
        }
        Thread.sleep(10);
      }
      assertThat(example.synchronous().get("key")).isEqualTo("v2");
    }
  }

  @Test
  void autoCloseShutsDownExecutor() {
    VirtualThreadCacheExample<String, String> example =
        new VirtualThreadCacheExample<>(key -> "val-" + key);

    assertThat(example.executor().isShutdown()).isFalse();
    example.close();
    assertThat(example.executor().isShutdown()).isTrue();
  }
}
