/*
 * Copyright 2019 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.executor;
import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Issue #298: Stale data when using Expiry
 * <p>
 * When a future value in an AsyncCache is in-flight, the entry has an infinite expiration time to
 * disable eviction. When it completes, a callback performs a no-op write into the cache to
 * update its metadata (expiration, weight, etc.). This may race with a reader who obtains a
 * completed future, reads the current duration as infinite, and tries to set the expiration time
 * accordingly (to indicate no change). If the writer completes before the reader updates, then we
 * encounter an ABA problem where the entry is set to never expire.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Isolated
final class Issue298Test {
  private static final long EXPIRE_NS = Duration.ofDays(1).toNanos();
  private static final String TEST_KEY = "key";

  @Test
  @SuppressWarnings({"FutureReturnValueIgnored", "UndefinedEquals"})
  void readDuringCreate() {
    var flags = new Flags();
    var cache = makeAsyncCache(flags);
    var policy = cache.synchronous().policy().expireVariably().orElseThrow();

    // Loaded value and waiting at expireAfterCreate (expire: infinite)
    var initialValue = cache.get(TEST_KEY);
    assertThat(initialValue).isNotNull();

    await().untilTrue(flags.startedLoad);
    flags.doLoad.set(true);
    await().untilTrue(flags.startedCreate);

    // Async read trying to wait at expireAfterRead
    var reader = CompletableFuture.runAsync(() -> {
      do {
        var value = cache.get(TEST_KEY);
        assertThat(value).isEqualTo(initialValue);
      } while (!flags.endRead.get());
    }, executor);

    // Ran expireAfterCreate (expire: infinite -> create)
    flags.doCreate.set(true);
    await().until(() -> policy.getExpiresAfter(TEST_KEY).orElseThrow().toNanos() <= EXPIRE_NS);
    await().untilTrue(flags.startedRead);

    // Ran reader (expire: create -> ?)
    flags.doRead.set(true);
    flags.endRead.set(true);
    reader.join();

    // Ensure expire is [expireAfterCreate], not [infinite]
    assertThat(policy.getExpiresAfter(TEST_KEY).orElseThrow().toNanos()).isAtMost(EXPIRE_NS);
  }

  private static AsyncLoadingCache<String, String> makeAsyncCache(Flags flags) {
    return Caffeine.newBuilder()
        .expireAfter(new Expiry<String, String>() {
          @Override public long expireAfterCreate(String key, String value, long currentTime) {
            flags.startedCreate.set(true);
            await().untilTrue(flags.doCreate);
            return EXPIRE_NS;
          }
          @CanIgnoreReturnValue
          @Override public long expireAfterUpdate(String key, String value,
              long currentTime, long currentDuration) {
            return currentDuration;
          }
          @CanIgnoreReturnValue
          @Override public long expireAfterRead(String key, String value,
              long currentTime, long currentDuration) {
            flags.startedRead.set(true);
            await().untilTrue(flags.doRead);
            return currentDuration;
          }
        }).executor(executor)
        .buildAsync(key -> {
          flags.startedLoad.set(true);
          await().untilTrue(flags.doLoad);
          return key + "'s value";
        });
  }

  private static final class Flags {
    private final AtomicBoolean startedCreate = new AtomicBoolean();
    private final AtomicBoolean startedLoad = new AtomicBoolean();
    private final AtomicBoolean startedRead = new AtomicBoolean();
    private final AtomicBoolean doCreate = new AtomicBoolean();
    private final AtomicBoolean endRead = new AtomicBoolean();
    private final AtomicBoolean doLoad = new AtomicBoolean();
    private final AtomicBoolean doRead = new AtomicBoolean();
  }
}
