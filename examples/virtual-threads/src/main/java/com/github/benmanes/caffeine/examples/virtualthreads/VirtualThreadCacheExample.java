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

import static java.util.Objects.requireNonNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

/**
 * An example demonstrating how to integrate Caffeine's {@link AsyncLoadingCache} with Java 21+
 * {@linkplain Executors#newVirtualThreadPerTaskExecutor() Virtual Threads} and {@link AsyncCacheLoader#asyncReload}.
 * <p>
 * In high-throughput, I/O-bound applications (e.g. calling microservices, querying remote databases,
 * or fetching third-party HTTP endpoints), traditional platform thread pools (such as fixed thread pools
 * or the common {@link java.util.concurrent.ForkJoinPool}) can easily become exhausted. When all threads
 * in a bounded pool block waiting on network I/O, subsequent cache loads and refreshes stall in queues,
 * causing latency spikes and potential cascading failures.
 * <p>
 * By configuring Caffeine with a virtual thread executor via {@link Caffeine#executor(Executor)},
 * each asynchronous cache load and background {@code asyncReload} executes on a lightweight virtual thread.
 * When blocking on I/O, the virtual thread unmounts from its carrier thread, freeing the underlying OS
 * thread to process other work. This enables thousands of concurrent background loads and refreshes
 * with minimal memory footprint and zero thread starvation.
 *
 * @author ronitgupta138 (Ronit Gupta)
 */
public final class VirtualThreadCacheExample<K, V> implements AutoCloseable {

  private static final System.Logger logger = System.getLogger(VirtualThreadCacheExample.class.getName());

  private final ExecutorService executor;
  private final AsyncLoadingCache<K, V> cache;

  /**
   * An {@link AsyncCacheLoader} that delegates compute/I/O tasks to the supplied functions and
   * runs asynchronous load and reload operations using the executor provided by Caffeine (which
   * runs on virtual threads).
   */
  public static final class VirtualThreadLoader<K, V> implements AsyncCacheLoader<K, V> {
    private final Function<K, V> loadFunction;
    private final BiFunction<K, V, V> reloadFunction;

    public VirtualThreadLoader(Function<K, V> loadFunction, BiFunction<K, V, V> reloadFunction) {
      this.loadFunction = requireNonNull(loadFunction);
      this.reloadFunction = requireNonNull(reloadFunction);
    }

    public VirtualThreadLoader(Function<K, V> loadFunction) {
      this(loadFunction, (key, oldValue) -> loadFunction.apply(key));
    }

    @Override
    public CompletableFuture<V> asyncLoad(K key, Executor executor) {
      return CompletableFuture.supplyAsync(() -> loadFunction.apply(key), executor);
    }

    @Override
    public CompletableFuture<V> asyncReload(K key, V oldValue, Executor executor) {
      return CompletableFuture.supplyAsync(() -> reloadFunction.apply(key, oldValue), executor);
    }
  }

  /**
   * Creates a cache configured with a virtual thread executor.
   *
   * @param loadFunction the blocking or compute function used to load missing keys
   */
  public VirtualThreadCacheExample(Function<K, V> loadFunction) {
    this(loadFunction, (key, oldValue) -> loadFunction.apply(key), null);
  }

  /**
   * Creates a cache configured with a virtual thread executor and optional automatic refresh.
   *
   * @param loadFunction the blocking or compute function used to load missing keys
   * @param reloadFunction the function used to refresh existing keys in the background
   * @param refreshAfterWrite optional duration after which stale entries are automatically reloaded
   */
  public VirtualThreadCacheExample(
      Function<K, V> loadFunction,
      BiFunction<K, V, V> reloadFunction,
      Duration refreshAfterWrite) {
    this.executor = Executors.newVirtualThreadPerTaskExecutor();

    var builder = Caffeine.newBuilder().executor(executor);
    if (refreshAfterWrite != null) {
      builder.refreshAfterWrite(refreshAfterWrite);
    }
    this.cache = builder.buildAsync(new VirtualThreadLoader<>(loadFunction, reloadFunction));
  }

  /** Returns the underlying {@link AsyncLoadingCache}. */
  public AsyncLoadingCache<K, V> cache() {
    return cache;
  }

  /** Returns a synchronous {@link LoadingCache} view of the underlying cache. */
  public LoadingCache<K, V> synchronous() {
    return cache.synchronous();
  }

  /**
   * Asynchronously retrieves the value for the given key, initiating a virtual-thread load if absent.
   */
  public CompletableFuture<V> get(K key) {
    return cache.get(key);
  }

  /**
   * Asynchronously retrieves values for the specified keys.
   */
  public CompletableFuture<Map<K, V>> getAll(Iterable<? extends K> keys) {
    return cache.getAll(keys);
  }

  /**
   * Triggers an asynchronous reload of the key using {@link AsyncCacheLoader#asyncReload} on a virtual thread.
   */
  public CompletableFuture<V> refresh(K key) {
    return cache.synchronous().refresh(key);
  }

  /** Returns the {@link ExecutorService} driving the cache's asynchronous operations. */
  public ExecutorService executor() {
    return executor;
  }

  @Override
  public void close() {
    executor.close();
  }

  /** Application entrypoint demonstrating virtual-thread cache loading. */
  public static void main(String[] args) {
    logger.log(Level.INFO, "Starting Virtual Threads Caffeine example...");

    try (var example = new VirtualThreadCacheExample<String, String>(
        key -> {
          logger.log(Level.INFO, "[Load] key=''{0}'' | virtual={1} | thread={2}",
              key, Thread.currentThread().isVirtual(), Thread.currentThread());
          try {
            Thread.sleep(Duration.ofMillis(100)); // Simulate remote I/O latency
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading cache entry", e);
          }
          return "value-for-" + key;
        },
        (key, oldValue) -> {
          logger.log(Level.INFO, "[Reload] key=''{0}'' (oldValue=''{1}'') | virtual={2} | thread={3}",
              key, oldValue, Thread.currentThread().isVirtual(), Thread.currentThread());
          try {
            Thread.sleep(Duration.ofMillis(100)); // Simulate remote I/O latency
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading cache entry", e);
          }
          return "refreshed-" + key;
        },
        Duration.ofSeconds(60))) {

      var initialFuture = example.get("user-42");
      logger.log(Level.INFO, "Dispatched load asynchronously, result: {0}", initialFuture.join());

      var refreshFuture = example.refresh("user-42");
      logger.log(Level.INFO, "Dispatched refresh asynchronously, result: {0}", refreshFuture.join());
    }
  }
}
