/*
 * Copyright 2011 Google Inc. All Rights Reserved.
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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.testng.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
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

import org.testng.annotations.Test;
import org.testng.log4testng.Logger;

import scala.concurrent.forkjoin.ThreadLocalRandom;

import com.github.benmanes.caffeine.ConcurrentTestHarness;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.google.common.testing.SerializableTester;

/**
 * A test to assert basic concurrency characteristics by validating the internal state after load.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MultiThreadedTest{
  private static final Logger logger = Logger.getLogger(MultiThreadedTest.class);

  private final int ITERATIONS = 40000;
  private final int MAX_SIZE = 50000;
  private final int NTHREADS = 20;
  private final int TIMEOUT = 30;

  @Test
  public void concurrent() {
    List<Integer> keys = newArrayList();
    for (int i = 0; i < ITERATIONS; i++) {
      keys.add(ThreadLocalRandom.current().nextInt(ITERATIONS / 100));
    }
    List<List<Integer>> sets = shuffle(NTHREADS, keys);
    Queue<String> failures = new ConcurrentLinkedQueue<>();
    Cache<Integer, Integer> cache = Caffeine.newBuilder()
        .maximumSize(MAX_SIZE)
        .build();
    executeWithTimeOut(cache, failures, () ->
        ConcurrentTestHarness.timeTasks(NTHREADS, new Thrasher(cache, sets, failures)));
  }

  @Test
  public void concurrent_weighted() {
    final Cache<Integer, List<Integer>> cache = Caffeine.newBuilder()
        .weigher(CacheWeigher.COLLECTION)
        .maximumWeight(NTHREADS)
        .build();
    Queue<String> failures = new ConcurrentLinkedQueue<>();
    Queue<List<Integer>> values = new ConcurrentLinkedQueue<>();
    for (int i = 1; i <= NTHREADS; i++) {
      Integer[] array = new Integer[i];
      Arrays.fill(array, Integer.MIN_VALUE);
      values.add(Arrays.asList(array));
    }
    executeWithTimeOut(cache, failures, () ->
        ConcurrentTestHarness.timeTasks(NTHREADS, () -> {
          List<Integer> value = values.poll();
          for (int i = 0; i < ITERATIONS; i++) {
            cache.put(i % 10, value);
          }
        }));
  }

  /**
   * Based on the passed in working set, creates N shuffled variants.
   *
   * @param samples the number of variants to create
   * @param workingSet the base working set to build from
   */
  private static <T> List<List<T>> shuffle(int samples, Collection<T> workingSet) {
    List<List<T>> sets = new ArrayList<>(samples);
    for (int i = 0; i < samples; i++) {
      List<T> set = new ArrayList<>(workingSet);
      Collections.shuffle(set);
      sets.add(set);
    }
    return sets;
  }

  /**
   * Executes operations against the cache to simulate random load.
   */
  private final class Thrasher implements Runnable {
    private final Cache<Integer, Integer> cache;
    private final List<List<Integer>> sets;
    private final Queue<String> failures;
    private final AtomicInteger index;

    public Thrasher(Cache<Integer, Integer> cache,
        List<List<Integer>> sets, Queue<String> failures) {
      this.index = new AtomicInteger();
      this.failures = failures;
      this.cache = cache;
      this.sets = sets;
    }

    @Override
    public void run() {
      Operation[] ops = Operation.values();
      int id = index.getAndIncrement();
      Random random = new Random();
      for (Integer key : sets.get(id)) {
        Operation operation = ops[random.nextInt(ops.length)];
        try {
          operation.execute(cache, key);
        } catch (RuntimeException e) {
          failures.add(String.format("Failed: key %s on operation %s", key, operation));
          throw e;
        } catch (Throwable t) {
          failures.add(String.format("Halted: key %s on operation %s", key, operation));
        }
      }
    }
  }

  /**
   * The public operations that can be performed on the cache.
   */
  private enum Operation {
    CONTAINS_KEY() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().containsKey(key);
      }
    },
    CONTAINS_VALUE() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().containsValue(key);
      }
    },
    IS_EMPTY() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().isEmpty();
      }
    },
    SIZE() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        checkState(cache.asMap().size() >= 0);
      }
    },
    GET() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().get(key);
      }
    },
    PUT() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().put(key, key);
      }
    },
    PUT_IF_ABSENT() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().putIfAbsent(key, key);
      }
    },
    REMOVE() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().remove(key);
      }
    },
    REMOVE_IF_EQUAL() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().remove(key, key);
      }
    },
    REPLACE() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().replace(key, key);
      }
    },
    REPLACE_IF_EQUAL() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().replace(key, key, key);
      }
    },
    CLEAR() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.asMap().clear();
      }
    },
    KEY_SET() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        for (Integer i : cache.asMap().keySet()) {
          checkNotNull(i);
        }
        cache.asMap().keySet().toArray(new Integer[cache.asMap().size()]);
      }
    },
    VALUES() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        for (Integer i : cache.asMap().values()) {
          checkNotNull(i);
        }
        cache.asMap().values().toArray(new Integer[cache.asMap().size()]);
      }
    },
    ENTRY_SET() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        for (Entry<Integer, Integer> entry : cache.asMap().entrySet()) {
          checkNotNull(entry);
          checkNotNull(entry.getKey());
          checkNotNull(entry.getValue());
        }
        cache.asMap().entrySet().toArray(new Entry[cache.asMap().size()]);
      }
    },
    HASHCODE() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.hashCode();
      }
    },
    EQUALS() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.equals(cache);
      }
    },
    TO_STRING() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        cache.toString();
      }
    },
    SERIALIZE() {
      @Override void execute(Cache<Integer, Integer> cache, Integer key) {
        SerializableTester.reserialize(cache);
      }
    };

    /**
     * Executes the operation.
     *
     * @param cache the cache to operate against
     * @param key the key to perform the operation with
     */
    abstract void execute(Cache<Integer, Integer> cache, Integer key);
  }

  /* ---------------- Utilities -------------- */

  private void executeWithTimeOut(Cache<?, ?> cache, Queue<String> failures, Callable<Long> task) {
    ExecutorService es = Executors.newSingleThreadExecutor();
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
      es.awaitTermination(10, SECONDS);
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
