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

import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Locale.US;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.google.errorprone.annotations.Var;

/**
 * A fuzzer that exercises the {@link Cache} by generating random sequences of cache operations
 * across different configurations. The cache's structural invariants are validated after each
 * batch of operations to detect corruption from unexpected feature interactions (eviction +
 * expiration, compute + invalidation, etc.).
 */
final class CacheFuzzer {
  private static final Operation[] OPERATIONS = Operation.values();

  // These tests require the environment variable JAZZER_FUZZ=1 to try new input arguments

  @FuzzTest(maxDuration = "5m")
  @SuppressWarnings("GuardedBy")
  void cache(FuzzedDataProvider data) {
    var ticker = new AtomicLong(data.consumeLong(0, Long.MAX_VALUE));
    var cache = buildCache(data, ticker);
    var errors = new ErrorTracker();

    int operations = data.consumeInt(0, 300);
    for (int i = 0; i < operations; i++) {
      var op = OPERATIONS[data.consumeInt(0, OPERATIONS.length - 1)];
      execute(op, data, cache, ticker, errors);
    }

    // Final validation after all operations
    validate(cache, errors);
  }

  /** Builds a cache with fuzzed configuration. */
  private static Cache<Integer, Integer> buildCache(FuzzedDataProvider data, AtomicLong ticker) {
    var builder = Caffeine.newBuilder().ticker(ticker::get);

    // Fuzz eviction: disabled, size-based, or weight-based
    int evictionMode = data.consumeInt(0, 2);
    if (evictionMode == 1) {
      builder.maximumSize(data.consumeInt(0, 50));
    } else if (evictionMode == 2) {
      builder.maximumWeight(data.consumeInt(0, 200));
      int weight = data.consumeInt(1, 5);
      builder.weigher((Integer key, Integer value) -> weight);
    }

    // Fuzz expiration: fixed write, fixed access, variable, or none
    int expiryMode = data.consumeInt(0, 3);
    if (expiryMode == 1) {
      builder.expireAfterWrite(fuzzDuration(data));
    } else if (expiryMode == 2) {
      builder.expireAfterAccess(fuzzDuration(data));
    } else if (expiryMode == 3) {
      long createNanos = data.consumeLong(0, TimeUnit.MINUTES.toNanos(5));
      long updateNanos = data.consumeLong(0, TimeUnit.MINUTES.toNanos(5));
      long readNanos = data.consumeLong(0, TimeUnit.MINUTES.toNanos(5));
      builder.expireAfter(new Expiry<Integer, Integer>() {
        @Override public long expireAfterCreate(Integer key, Integer value, long currentTime) {
          return createNanos;
        }
        @Override public long expireAfterUpdate(
            Integer key, Integer value, long currentTime, long currentDuration) {
          return updateNanos;
        }
        @Override public long expireAfterRead(
            Integer key, Integer value, long currentTime, long currentDuration) {
          return readNanos;
        }
      });
    }

    return builder.build();
  }

  /** Returns a fuzzed duration in the range [0, 5 minutes]. */
  private static Duration fuzzDuration(FuzzedDataProvider data) {
    return Duration.ofNanos(data.consumeLong(0, TimeUnit.MINUTES.toNanos(5)));
  }

  /** Executes a single fuzzed operation against the cache. */
  @SuppressWarnings("FutureReturnValueIgnored")
  private static void execute(Operation op, FuzzedDataProvider data,
      Cache<Integer, Integer> cache, AtomicLong ticker, ErrorTracker errors) {
    // Small key space (0-15) maximizes collisions and interesting interactions
    int key = data.consumeInt(0, 15);
    try {
      switch (op) {
        case GET_IF_PRESENT: {
          var value = cache.getIfPresent(key);
          if (value != null) {
            assertWithMessage("getIfPresent(%s) returned wrong type", key)
                .that(value).isInstanceOf(Integer.class);
          }
          break;
        }
        case PUT: {
          cache.put(key, data.consumeInt());
          break;
        }
        case GET_COMPUTE: {
          int loadedValue = data.consumeInt();
          var value = cache.get(key, k -> loadedValue);
          assertWithMessage("get(%s, loader) should not return null", key)
              .that(value).isNotNull();
          break;
        }
        case INVALIDATE: {
          cache.invalidate(key);
          assertWithMessage("key %s should be absent after invalidate", key)
              .that(cache.getIfPresent(key)).isNull();
          break;
        }
        case INVALIDATE_ALL: {
          cache.invalidateAll();
          assertWithMessage("cache should be empty after invalidateAll")
              .that(cache.estimatedSize()).isEqualTo(0);
          break;
        }
        case CLEAN_UP: {
          cache.cleanUp();
          break;
        }
        case COMPUTE: {
          int value = data.consumeInt();
          boolean remove = data.consumeBoolean();
          cache.asMap().compute(key, (k, v) -> remove ? null : value);
          break;
        }
        case MERGE: {
          int value = data.consumeInt();
          cache.asMap().merge(key, value, (v1, v2) -> data.consumeBoolean() ? null : v1 + v2);
          break;
        }
        case PUT_IF_ABSENT: {
          cache.asMap().putIfAbsent(key, data.consumeInt());
          break;
        }
        case REMOVE: {
          cache.asMap().remove(key);
          break;
        }
        case REPLACE: {
          cache.asMap().replace(key, data.consumeInt());
          break;
        }
        case CLEAR: {
          cache.asMap().clear();
          assertWithMessage("cache should be empty after clear")
              .that(cache.estimatedSize()).isEqualTo(0);
          break;
        }
        case ADVANCE_TICKER: {
          ticker.addAndGet(data.consumeLong(0, TimeUnit.MINUTES.toNanos(1)));
          break;
        }
        case SIZE_CHECK: {
          cache.cleanUp();
          checkSize(cache);
          break;
        }
        case ENTRY_CHECK: {
          // Every key visible in asMap should be retrievable
          for (var entry : cache.asMap().entrySet()) {
            assertWithMessage("value for key %s in asMap", entry.getKey())
                .that(entry.getValue()).isNotNull();
          }
          break;
        }
      }
    } catch (RuntimeException e) {
      // Cache operations throwing these are potential bugs; track them
      errors.record(op, key, e);
    }
  }

  /** Validates all structural invariants after the operation sequence completes. */
  private static void validate(Cache<Integer, Integer> cache, ErrorTracker errors) {
    cache.cleanUp();
    checkSize(cache);

    // Every entry should have non-null key and value (strong references)
    for (var entry : cache.asMap().entrySet()) {
      assertWithMessage("key should not be null").that(entry.getKey()).isNotNull();
      assertWithMessage("value for key %s", entry.getKey()).that(entry.getValue()).isNotNull();
    }

    // Eviction policy invariants
    cache.policy().eviction().ifPresent(eviction -> {
      assertWithMessage("size <= maximum after cleanUp")
          .that(cache.estimatedSize()).isAtMost(eviction.getMaximum());
      eviction.weightedSize().ifPresent(weightedSize -> {
        assertWithMessage("weightedSize should be non-negative")
            .that(weightedSize).isAtLeast(0L);
        assertWithMessage("weightedSize <= maximum")
            .that(weightedSize).isAtMost(eviction.getMaximum());
      });
    });

    // If the cache is a BoundedLocalCache, run the deep structural check
    if (cache.asMap() instanceof BoundedLocalCache<?, ?>) {
      @SuppressWarnings("unchecked")
      var bounded = (BoundedLocalCache<Integer, Integer>) cache.asMap();
      checkBounded(bounded);
    }

    // Report any unexpected exceptions from operations
    errors.assertEmpty();
  }

  /** Checks that the cache size is consistent. */
  private static void checkSize(Cache<Integer, Integer> cache) {
    long size = cache.estimatedSize();
    assertWithMessage("estimatedSize should be non-negative").that(size).isAtLeast(0L);
    assertWithMessage("asMap.size should equal estimatedSize")
        .that((long) cache.asMap().size()).isEqualTo(size);
  }

  /** Checks the internal structure of a bounded cache. */
  @SuppressWarnings("GuardedBy")
  private static void checkBounded(BoundedLocalCache<Integer, Integer> bounded) {
    // Drain pending work
    for (int i = 0; i < 5; i++) {
      bounded.cleanUp();
      if (bounded.writeBuffer.isEmpty()) {
        break;
      }
    }

    // Data map size should match
    assertWithMessage("data.size == cache.size")
        .that(bounded.data.size()).isEqualTo((int) bounded.estimatedSize());

    if (bounded.evicts()) {
      bounded.evictionLock.lock();
      try {
        assertWithMessage("weightedSize <= maximum")
            .that(bounded.weightedSize()).isAtMost(bounded.maximum());
        assertWithMessage("weightedSize should be non-negative")
            .that(bounded.weightedSize()).isAtLeast(0);
        assertWithMessage("windowWeightedSize <= windowMaximum")
            .that(bounded.windowWeightedSize()).isAtMost(bounded.windowMaximum());
        assertWithMessage("mainProtectedWeightedSize <= mainProtectedMaximum")
            .that(bounded.mainProtectedWeightedSize())
            .isAtMost(bounded.mainProtectedMaximum());
      } finally {
        bounded.evictionLock.unlock();
      }
    }

    // Each node should have a non-null key and value
    for (var node : bounded.data.values()) {
      assertWithMessage("node.key").that(node.getKey()).isNotNull();
      assertWithMessage("node.value for key %s", node.getKey())
          .that(node.getValue()).isNotNull();
      assertWithMessage("node.weight for key %s", node.getKey())
          .that(node.getWeight()).isAtLeast(0);
    }
  }

  /** Tracks unexpected exceptions from cache operations for deferred assertion. */
  private static final class ErrorTracker {
    @Var private String firstError = "";
    @Var private int count;

    void record(Operation op, int key, Throwable error) {
      count++;
      if (count == 1) {
        firstError = String.format(US, "%s(key=%d): %s", op, key, error);
      }
    }

    void assertEmpty() {
      assertWithMessage("Unexpected exceptions during cache operations: %s", firstError)
          .that(count).isEqualTo(0);
    }
  }

  private enum Operation {
    GET_IF_PRESENT,
    PUT,
    GET_COMPUTE,
    INVALIDATE,
    INVALIDATE_ALL,
    CLEAN_UP,
    COMPUTE,
    MERGE,
    PUT_IF_ABSENT,
    REMOVE,
    REPLACE,
    CLEAR,
    ADVANCE_TICKER,
    SIZE_CHECK,
    ENTRY_CHECK,
  }
}
