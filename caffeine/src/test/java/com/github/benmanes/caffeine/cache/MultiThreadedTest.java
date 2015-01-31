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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.IsValidCache.validCache;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.testng.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
import org.testng.log4testng.Logger;

import scala.concurrent.forkjoin.ThreadLocalRandom;

import com.github.benmanes.caffeine.ConcurrentTestHarness;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.SerializableTester;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * A test to assert basic concurrency characteristics by validating the internal state after load.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(groups = "slow", dataProviderClass = CacheProvider.class)
public final class MultiThreadedTest{
  private static final Logger logger = Logger.getLogger(MultiThreadedTest.class);

  private static final int ITERATIONS = 40000;
  private static final int NTHREADS = 20;
  private static final int TIMEOUT = 30;

  private final List<List<Integer>> workingSets;

  public MultiThreadedTest() {
    List<Integer> keys = IntStream.range(0, ITERATIONS).boxed()
        .map(i -> ThreadLocalRandom.current().nextInt(ITERATIONS / 100))
        .collect(Collectors.toList());
    workingSets = shuffle(NTHREADS, keys);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(maximumSize = MaximumSize.FULL, stats = Stats.ENABLED,
      expireAfterAccess = Expire.FOREVER, expireAfterWrite = Expire.FOREVER,
      population = Population.EMPTY, removalListener = Listener.DEFAULT)
  public void concurrent(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Queue<String> failures = new ConcurrentLinkedQueue<>();
    Thrasher thrasher = new Thrasher(cache, workingSets, failures);
    executeWithTimeOut(cache, failures, () -> ConcurrentTestHarness.timeTasks(NTHREADS, thrasher));
    assertThat(failures.isEmpty(), is(true));
  }

  /**
   * Based on the passed in working set, creates N shuffled variants.
   *
   * @param samples the number of variants to create
   * @param baseline the base working set to build from
   */
  private static <T> List<List<T>> shuffle(int samples, Collection<T> baseline) {
    List<List<T>> workingSets = new ArrayList<>(samples);
    for (int i = 0; i < samples; i++) {
      List<T> workingSet = new ArrayList<>(baseline);
      Collections.shuffle(workingSet);
      workingSets.add(ImmutableList.copyOf(workingSet));
    }
    return ImmutableList.copyOf(workingSets);
  }

  /** Executes operations against the cache to simulate random load. */
  private final class Thrasher implements Runnable {
    private final LoadingCache<Integer, Integer> cache;
    private final List<List<Integer>> sets;
    private final Queue<String> failures;
    private final AtomicInteger index;

    public Thrasher(LoadingCache<Integer, Integer> cache,
        List<List<Integer>> sets, Queue<String> failures) {
      this.index = new AtomicInteger();
      this.failures = failures;
      this.cache = cache;
      this.sets = sets;
    }

    @Override
    public void run() {
      Random random = new Random();
      int id = index.getAndIncrement();
      for (Integer key : sets.get(id)) {
        CacheOperation<Integer, Integer> operation = operations[random.nextInt(operations.length)];
        try {
          operation.execute(cache, key, key);
        } catch (Throwable t) {
          failures.add(String.format("Failed: key %s on operation %s", key, operation));
          throw t;
        }
      }
    }
  }

  /** The public operations that can be performed on the cache. */
  interface CacheOperation<K, V> {
    void execute(LoadingCache<Integer, Integer> cache, Integer key, Integer value);
  }

  @SuppressWarnings("unchecked")
  CacheOperation<Integer, Integer>[] operations = new CacheOperation[] {
      // LoadingCache
      (cache, key, value) -> cache.get(key),
      (cache, key, value) -> cache.getAll(ImmutableList.of(key)),
      (cache, key, value) -> cache.refresh(key),

      // Cache
      (cache, key, value) -> cache.getIfPresent(key),
      (cache, key, value) -> cache.get(key, Function.identity()),
      (cache, key, value) -> cache.getAllPresent(ImmutableList.of(key)),
      (cache, key, value) -> cache.put(key, value),
      (cache, key, value) -> cache.putAll(ImmutableMap.of(key, value)),
      (cache, key, value) -> cache.invalidate(key),
      (cache, key, value) -> cache.invalidateAll(ImmutableList.of(key)),
      (cache, key, value) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          cache.invalidateAll();
        }
      },
      (cache, key, value) -> Preconditions.checkState(cache.estimatedSize() >= 0),
      (cache, key, value) -> cache.stats(),
      (cache, key, value) -> cache.cleanUp(),

      // Map
      (cache, key, value) -> cache.asMap().containsKey(key),
      (cache, key, value) -> cache.asMap().containsValue(value),
      (cache, key, value) -> cache.asMap().isEmpty(),
      (cache, key, value) -> Preconditions.checkState(cache.asMap().size() >= 0),
      (cache, key, value) -> cache.asMap().get(key),
      (cache, key, value) -> cache.asMap().put(key, value),
      (cache, key, value) -> cache.asMap().putAll(ImmutableMap.of(key, value)),
      (cache, key, value) -> cache.asMap().putIfAbsent(key, value),
      (cache, key, value) -> cache.asMap().remove(key),
      (cache, key, value) -> cache.asMap().remove(key, value),
      (cache, key, value) -> cache.asMap().replace(key, value),
      (cache, key, value) -> cache.asMap().replace(key, value, value),
      (cache, key, value) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          cache.asMap().clear();
        }
      },
      (cache, key, value) -> cache.asMap().keySet().toArray(new Object[cache.asMap().size()]),
      (cache, key, value) -> cache.asMap().values().toArray(new Object[cache.asMap().size()]),
      (cache, key, value) -> cache.asMap().entrySet().toArray(new Entry[cache.asMap().size()]),
      (cache, key, value) -> cache.hashCode(),
      (cache, key, value) -> cache.equals(cache),
      (cache, key, value) -> cache.toString(),
      (cache, key, value) -> { // expensive so do it less frequently
        int random = ThreadLocalRandom.current().nextInt();
        if ((random & 255) == 0) {
          SerializableTester.reserialize(cache);
        }
      },
  };

  /* ---------------- Utilities -------------- */

  private void executeWithTimeOut(Cache<?, ?> cache, Queue<String> failures, Callable<Long> task) {
    ExecutorService es = Executors.newSingleThreadExecutor(
        new ThreadFactoryBuilder().setDaemon(true).build());
    Future<Long> future = es.submit(task);
    try {
      long timeNS = future.get(TIMEOUT, TimeUnit.SECONDS);
      logger.debug("\nExecuted in " + TimeUnit.NANOSECONDS.toSeconds(timeNS) + " second(s)");
      assertThat((Cache<?, ?>) cache, is(validCache()));
    } catch (ExecutionException e) {
      fail("Exception during test: " + e.toString(), e);
    } catch (TimeoutException e) {
      handleTimout(cache, failures, es, e);
    } catch (InterruptedException e) {
      fail("", e);
    }
  }

  private void handleTimout(Cache<?, ?> cache, Queue<String> failures,
      ExecutorService es, TimeoutException e) {
    for (StackTraceElement[] trace : Thread.getAllStackTraces().values()) {
      for (StackTraceElement element : trace) {
        logger.info("\tat " + element);
      }
      if (trace.length > 0) {
        logger.info("------");
      }
    }
    es.shutdownNow();
    try {
      es.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      fail("", ex);
    }

    logger.debug("Cached Elements: " + cache.toString());
    for (String failure : failures) {
      logger.debug(failure);
    }
    fail("Spun forever", e);
  }
}
