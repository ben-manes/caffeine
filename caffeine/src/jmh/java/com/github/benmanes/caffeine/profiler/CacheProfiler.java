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

import com.github.benmanes.caffeine.cache.CacheType;
import com.github.benmanes.caffeine.cache.tracing.Tracer;

/**
 * A hook for profiling caches.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
public final class CacheProfiler extends ProfilerHook {
  static final int NUM_THREADS = 25;
  static final int MAX_SIZE = 2 * NUM_THREADS;
  static final CacheType type = CacheType.Caffeine;

  final Map<Long, Long> map;

  CacheProfiler() {
    System.setProperty(Tracer.TRACING_ENABLED, "false");
    map = type.create(MAX_SIZE, NUM_THREADS);
  }

  @Override
  protected void profile() {
    Long id = Thread.currentThread().getId();
    map.put(id, id);
    for (;;) {
      map.get(id);
      calls.increment();
    }
  }

  public static void main(String[] args) {
    CacheProfiler profile = new CacheProfiler();
    profile.run();
  }
}
