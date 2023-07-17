/*
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
package com.github.benmanes.caffeine.jcache.issues;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import java.io.Serializable;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.cache.Cache;
import javax.cache.Caching;
import javax.cache.configuration.FactoryBuilder;
import javax.cache.configuration.MutableCacheEntryListenerConfiguration;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.integration.CacheLoader;
import javax.cache.spi.CachingProvider;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Issue #1065: Cache listeners including writes to caches deadlock when used in ForkJoinPool
 * <p>
 * The JCache listener is documented as deadlock prone if it mutates any cache, such as by causing a
 * cycle. If the listener is marked as <code>synchronous</code> then the caller must wait for the
 * events that it emitted to complete, where the listener is required to operate on events in order
 * that they occurred for a given key. A user assumption is that no deadlock should occur when a
 * primary cache uses a listener to write into a secondary cache because no cycle was created.
 * <p>
 * Caffeine publishes the event notification within the cache's computation to maintain key order,
 * and waits for the acknowledgement outside of the computation to avoid blocking other writes. This
 * signal is captured in a {@link ThreadLocal} list so that events may be sent from different
 * classes within the execution flow, such as by the cache loader for creation or a removal listener
 * if the entry existed but had expired.
 * <p>
 * Previously a deadlock could occur where the listener's write into a secondary cache could observe
 * the primary cache's pending acknowledgement and wait as part of its completion set. This happened
 * because a <code>static</code> {@link ThreadLocal} field was used, so if the listener ran on the
 * primary cache's thread this leak was observed by the secondary cache. That can happen when using
 * a same-thread executor, a caller-runs rejection handler, or if the joining
 * {@link CompletableFuture} waits by cooperatively assisting a {@link ForkJoinPool}. This deadlock
 * was mitigated by using a per-cache {@link ThreadLocal} field instead so that the completion
 * signals are isolated.
 *
 * @author monitorjbl (Taylor Jones)
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Issue1065Test {
  private static final int NUM_THREADS = 5;
  private static final CachingProvider provider =
      Caching.getCachingProvider(CaffeineCachingProvider.class.getName());

  Cache<String, String> fallback;
  Cache<String, String> cache;
  ExecutorService executor;

  @BeforeMethod
  public void before() {
    fallback = provider.getCacheManager()
        .createCache("fallback", new MutableConfiguration<String, String>());
    cache = provider.getCacheManager().createCache("primary",
        new MutableConfiguration<String, String>()
            .addCacheEntryListenerConfiguration(new MutableCacheEntryListenerConfiguration<>(
                FactoryBuilder.factoryOf(new Listener()), FactoryBuilder.factoryOf(event -> true),
                /* isOldValueRequired */ true, /* isSynchronous */ true))
            .setCacheLoaderFactory(new FactoryBuilder.SingletonFactory<>(new Loader()))
            .setReadThrough(true));
    executor = Executors.newWorkStealingPool(NUM_THREADS);
  }

  @AfterMethod
  public void after() {
    executor.shutdownNow();
    fallback.close();
    cache.close();
  }

  @Test
  public void deadlock() throws Exception {
    for (int i = 0; i < 500; i++) {
      var threads = new ConcurrentLinkedQueue<Thread>();
      var futures = new CompletableFuture<?>[NUM_THREADS];
      for (int j = 0; j < NUM_THREADS; j++) {
        futures[j] = CompletableFuture.runAsync(() -> {
          threads.add(Thread.currentThread());
          cache.get("key");
        }, executor);
      }
      try {
        CompletableFuture.allOf(futures).get(1, TimeUnit.SECONDS);
        cache.removeAll();
      } catch (TimeoutException e) {
        fail(i, threads);
      }
    }
  }

  private static void fail(int iteration, Collection<Thread> threads) {
    var failure = new AssertionError("Deadlock detected at iteration #" + iteration);
    failure.setStackTrace(new StackTraceElement[0]);
    for (var thread : threads) {
      var error = new Exception();
      failure.addSuppressed(error);
      error.setStackTrace(thread.getStackTrace());
    }
    throw failure;
  }

  private final class Listener implements CacheEntryCreatedListener<String, String>, Serializable {
    private static final long serialVersionUID = 1L;

    @Override public void onCreated(
        Iterable<CacheEntryEvent<? extends String, ? extends String>> events) {
      events.forEach(event -> fallback.put(event.getKey(), event.getValue()));
    }
  }

  private static final class Loader implements CacheLoader<String, String> {
    @CanIgnoreReturnValue
    @Override public String load(String key) {
      return key;
    }
    @Override public ImmutableMap<String, String> loadAll(Iterable<? extends String> keys) {
      return Streams.stream(keys).collect(toImmutableMap(Objects::toString, this::load));
    }
  }
}
