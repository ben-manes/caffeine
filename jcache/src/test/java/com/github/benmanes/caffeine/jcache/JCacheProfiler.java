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
package com.github.benmanes.caffeine.jcache;

import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;

import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import javax.cache.Cache;
import javax.cache.Caching;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * A hook for profiling the JCache adapter.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
public final class JCacheProfiler {
  private static final int THREADS = 10;
  private static final int KEYS = 10_000;
  private static final boolean READ = true;

  private final LongAdder count;
  private final Random random;

  JCacheProfiler() {
    random = new Random();
    count = new LongAdder();
  }

  public void start() {
    var configuration = new CaffeineConfiguration<Integer, Boolean>()
        .setMaximumSize(OptionalLong.of(KEYS));
    try (var provider = Caching.getCachingProvider(CaffeineCachingProvider.class.getName());
         var cacheManager = provider.getCacheManager(
             provider.getDefaultURI(), provider.getDefaultClassLoader());
         var cache = cacheManager.createCache("profiler", configuration)) {
      run(cache);
    }
  }

  private void run(Cache<Integer, Boolean> cache) {
    for (int i = 0; i < KEYS; i++) {
      cache.put(i, true);
    }
    Runnable task = () -> {
      for (int i = random.nextInt(); ; i++) {
        Integer key = Math.abs(i % KEYS);
        if (READ) {
          requireNonNull(cache.get(key));
        } else {
          cache.put(key, true);
        }
        count.increment();
      }
    };

    @SuppressWarnings("PMD.CloseResource")
    var executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
        .setPriority(Thread.MIN_PRIORITY).setDaemon(true).build());
    try {
      scheduleStatusTask();
      for (int i = 0; i < THREADS; i++) {
        executor.execute(task);
      }
    } finally {
      executor.shutdown();
      Uninterruptibles.awaitTerminationUninterruptibly(executor);
    }
  }

  @CanIgnoreReturnValue
  @SuppressWarnings("SystemOut")
  private ScheduledFuture<?> scheduleStatusTask() {
    var stopwatch = Stopwatch.createStarted();
    return Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
      long iterations = count.longValue();
      long rate = iterations / stopwatch.elapsed(TimeUnit.SECONDS);
      System.out.printf(US, "%s - %,d [%,d / sec]%n", stopwatch, iterations, rate);
    }, 5, 5, TimeUnit.SECONDS);
  }

  public static void main(String[] args) {
    new JCacheProfiler().start();
  }
}
