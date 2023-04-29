/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.examples.graalnative;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

public class Application {
  private final Cache<Integer, Integer> cache;

  public Application(int maximumSize) {
    cache = Caffeine.newBuilder()
        .maximumSize(maximumSize)
        .executor(Runnable::run)
        .recordStats()
        .build();
  }

  public void run(int iterations, int modulus) {
    for (int i = 0; i < iterations; i++) {
      cache.get(i % modulus, key -> key);
    }
  }

  public CacheStats stats() {
    return cache.stats();
  }

  public static void main(String[] args) {
    var app = new Application(500);
    app.run(1_000_000, 1_000);

    System.out.println("Caffeine " + Caffeine.class.getPackage().getImplementationVersion());
    System.out.println(app.stats());
  }
}
