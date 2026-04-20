/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.Expiry.accessing;
import static com.github.benmanes.caffeine.cache.Expiry.writing;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Random;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import com.github.benmanes.caffeine.cache.impl.CaffeineCache;

import site.ycsb.generator.ScrambledZipfianGenerator;

/**
 * Probes hot-entry cache-line contention when reading an entry.
 * <p>
 * {@link #hot} pins every thread to the same key to maximize cache-line contention;
 * {@link #spread} rotates per-thread keys to measure the non-contended cost of the same code path.
 * <p>
 * {@snippet lang="shell" :
 * ./gradlew jmh -PincludePattern=HotEntryBenchmark -PjavaVersion=25 --rerun
 * }
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
@SuppressWarnings({"CanonicalAnnotationSyntax", "IdentifierName",
    "LexicographicalAnnotationAttributeListing", "NotNullFieldNotInitialized", "unused"})
public class HotEntryBenchmark {
  static final int SIZE = (2 << 14);
  static final int MASK = SIZE - 1;
  static final int ITEMS = SIZE / 3;
  static final Integer HOT_KEY = 0;

  @Param Config config;

  BasicCache<Integer, Boolean> cache;
  Integer[] keys;

  @State(Scope.Thread)
  public static class ThreadState {
    static final Random random = new Random();
    int index = random.nextInt();
  }

  @Setup
  public void setup() {
    keys = new Integer[SIZE];
    var generator = new ScrambledZipfianGenerator(ITEMS);
    for (int i = 1; i < SIZE; i++) {
      keys[i] = generator.nextValue().intValue();
    }
    keys[0] = HOT_KEY;

    cache = config.create();
    for (int i = 0; i < SIZE; i++) {
      cache.put(keys[i], true);
    }
  }

  /** Every thread hammers the same key to maximize cache-line contention. */
  @Benchmark @Threads(Threads.MAX)
  public @Nullable Boolean hot() {
    return cache.get(HOT_KEY);
  }

  /** Each thread rotates keys to maximize true-sharing on Nodes. */
  @Benchmark @Threads(Threads.MAX)
  public @Nullable Boolean spread(ThreadState ts) {
    return cache.get(keys[ts.index++ & MASK]);
  }

  @SuppressWarnings("ImmutableEnumChecker")
  public enum Config {
    weakKey(Caffeine::weakKeys),
    unbounded(builder -> builder),
    weakValue(Caffeine::weakValues),
    maximumSize(builder -> builder.maximumSize(SIZE)),
    expireAfterWrite(builder -> builder.expireAfterWrite(Duration.ofHours(1))),
    expireAfterAccess(builder -> builder.expireAfterAccess(Duration.ofHours(1))),
    variableWriting(builder -> builder.expireAfter(writing((k, v) -> Duration.ofHours(1)))),
    variableAccessing(builder -> builder.expireAfter(accessing((k, v) -> Duration.ofHours(1))));

    private final UnaryOperator<Caffeine<Object, Object>> configurator;

    Config(UnaryOperator<Caffeine<Object, Object>> configurator) {
      this.configurator = requireNonNull(configurator);
    }

    <K, V> BasicCache<K, V> create() {
      return new CaffeineCache<>(configurator.apply(Caffeine.newBuilder()
          .initialCapacity(SIZE).executor(Runnable::run)));
    }
  }
}
