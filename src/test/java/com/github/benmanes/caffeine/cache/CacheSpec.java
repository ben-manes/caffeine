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
package com.github.benmanes.caffeine.cache;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;

/**
 * The cache test specification so that a {@link org.testng.annotations.DataProvider} can construct
 * the maximum number of cache combinations to test against.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Target(METHOD) @Retention(RUNTIME)
public @interface CacheSpec {

  /** The initial capacities, each resulting in a new combination. */
  int[] initialCapacity() default {};

  /** The maximum size, each resulting in a new combination. */
  long[] maximumSize() default {};

  /**
   * The number of entries to populate the cache with. The keys and values are integers starting
   * from 0, with the value being the negated key. Each configuration results in a new combination.
   */
  Population[] population() default {
      Population.EMPTY, Population.SINGLETON, Population.PARTIAL, Population.FULL };

  /** The removal listeners, each resulting in a new combination. */
  Class<RemovalListener<?, ?>>[] removalListener() default {};

  /** The executors, each resulting in a new combination. */
  Class<Executor>[] executor() default {};

  /** The population scenarios. */
  enum Population {
    EMPTY() {
      @Override public void populate(Cache<Integer, Integer> cache, int maximum) {}
    },
    SINGLETON() {
      @Override public void populate(Cache<Integer, Integer> cache, int maximum) {
        cache.put(0, 0);
      }
    },
    PARTIAL() {
      @Override public void populate(Cache<Integer, Integer> cache, int maximum) {
        for (int i = 0; i < (maximum / 2); i++) {
          cache.put(i, -i);
        }
      }
    },
    FULL() {
      @Override public void populate(Cache<Integer, Integer> cache, int maximum) {
        for (int i = 0; i < maximum; i++) {
          cache.put(i, -i);
        }
      }
    };

    abstract void populate(Cache<Integer, Integer> cache, int maximum);
  }
}
