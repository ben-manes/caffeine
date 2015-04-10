/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.profiler;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import com.github.benmanes.caffeine.cache.CacheType;
import com.github.benmanes.caffeine.cache.tracing.Tracer;

/**
 * A hook for profiling caches.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
public final class CacheProfiler extends ProfilerHook {
  static final int MAX_SIZE = 2 * NUM_THREADS;
  static final CacheType type = CacheType.Caffeine;

  final Map<Long, Long> map;
  final boolean reads;

  CacheProfiler() {
    System.setProperty(Tracer.TRACING_ENABLED, "false");
    map = type.create(MAX_SIZE, NUM_THREADS);
    reads = true;
  }

  @Override
  protected void profile() {
    if (reads) {
      reads();
    } else {
      writes();
    }
  }

  /** Spins forever reading from the cache. */
  private void reads() {
    Long id = Thread.currentThread().getId();
    map.put(id, id);
    for (;;) {
      map.get(id);
      calls.increment();
    }
  }

  /** Spins forever writing into the cache. */
  private void writes() {
    Random random = ThreadLocalRandom.current();
    for (;;) {
      Long key = new Long(random.nextLong());
      map.put(key, key);
      calls.increment();
    }
  }

  public static void main(String[] args) {
    CacheProfiler profile = new CacheProfiler();
    profile.run();
  }
}
