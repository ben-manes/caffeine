/*
 * Copyright 2020 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.issues;

import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.DAEMON_FACTORY;
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.timeTasks;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import site.ycsb.generator.ScrambledZipfianGenerator;

/**
 * Issue #412: Incorrect removal cause when expiration races with removal
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(groups = "isolated")
public final class Issue412Test {
  private static final int NUM_THREADS = 5;

  private Cache<Integer, Boolean> cache;
  private ExecutorService executor;
  private AtomicBoolean collected;
  private Integer[] ints;
  private Random random;

  @BeforeMethod
  public void before() {
    executor = Executors.newCachedThreadPool(DAEMON_FACTORY);
    collected = new AtomicBoolean();
    cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofNanos(10))
        .removalListener((k, v, cause) -> {
          if (cause == RemovalCause.COLLECTED) {
            collected.set(true);
          }
        })
        .executor(executor)
        .build();
    ints = generateSequence();
    random = new Random();
  }

  @Test
  public void expire_remove() {
    timeTasks(NUM_THREADS, this::addRemoveAndExpire);
    shutdownAndAwaitTermination(executor, 1, TimeUnit.MINUTES);
    assertThat(collected.get()).isFalse();
  }

  private void addRemoveAndExpire() {
    int mask = (ints.length - 1);
    int index = random.nextInt();
    for (int i = 0; i < (10 * ints.length); i++) {
      Integer key = ints[index++ & mask];
      cache.put(key, Boolean.TRUE);
      cache.invalidate(key);
    }
  }

  private static Integer[] generateSequence() {
    var ints = new Integer[2 << 14];
    var generator = new ScrambledZipfianGenerator(ints.length / 3);
    for (int i = 0; i < ints.length; i++) {
      ints[i] = generator.nextValue().intValue();
    }
    return ints;
  }
}
