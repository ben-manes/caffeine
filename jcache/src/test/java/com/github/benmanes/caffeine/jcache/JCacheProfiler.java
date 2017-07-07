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

import static java.util.Objects.requireNonNull;

import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;

import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * A hook for profiling the JCache adapter.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
public final class JCacheProfiler {
  private static final int THREADS = 10;
  private static final int KEYS = 10_000;
  private static final boolean READ = true;

  private final Cache<Integer, Boolean> cache;
  private final Executor executor;
  private final LongAdder count;
  private final Random random;

  JCacheProfiler() {
    random = new Random();
    count = new LongAdder();
    CachingProvider provider = Caching.getCachingProvider(CaffeineCachingProvider.class.getName());
    CacheManager cacheManager = provider.getCacheManager(
        provider.getDefaultURI(), provider.getDefaultClassLoader());
    cache = cacheManager.createCache("profiler", new MutableConfiguration<>());
    executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
        .setPriority(Thread.MIN_PRIORITY).setDaemon(true).build());
  }

  public void start() {
    for (Integer i = 0; i < KEYS; i++) {
      cache.put(i, Boolean.TRUE);
    }
    Runnable task = () -> {
      for (int i = random.nextInt(); ; i++) {
        Integer key = Math.abs(i % KEYS);
        if (READ) {
          requireNonNull(cache.get(key));
        } else {
          cache.put(key, Boolean.TRUE);
        }
        count.increment();
      }
    };

    scheduleStatusTask();
    for (int i = 0; i < THREADS; i++) {
      executor.execute(task);
    }
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void scheduleStatusTask() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> {
      long count = this.count.longValue();
      long rate = count / stopwatch.elapsed(TimeUnit.SECONDS);
      System.out.printf("%s - %,d [%,d / sec]%n", stopwatch, count, rate);
    }, 5, 5, TimeUnit.SECONDS);
  }

  public static void main(String[] args) {
    new JCacheProfiler().start();
  }
}
