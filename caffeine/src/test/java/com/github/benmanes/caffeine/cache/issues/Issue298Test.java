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

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Policy.VarExpiration;
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
@Test(groups = "isolated")
public final class Issue298Test {
  static final long EXPIRE_NS = Duration.ofDays(1).toNanos();

  AtomicBoolean startedLoad;
  AtomicBoolean doLoad;

  AtomicBoolean startedCreate;
  AtomicBoolean doCreate;

  AtomicBoolean startedRead;
  AtomicBoolean doRead;
  AtomicBoolean endRead;

  AsyncLoadingCache<String, String> cache;
  VarExpiration<String, String> policy;
  String key;

  @BeforeMethod
  public void before() {
    startedCreate = new AtomicBoolean();
    startedLoad = new AtomicBoolean();
    startedRead = new AtomicBoolean();
    doCreate = new AtomicBoolean();
    endRead = new AtomicBoolean();
    doLoad = new AtomicBoolean();
    doRead = new AtomicBoolean();

    key = "key";
    cache = makeAsyncCache();
    policy = cache.synchronous().policy().expireVariably().orElseThrow();
  }

  @AfterMethod
  public void after() {
    endRead.set(true);
  }

  @Test
  @SuppressWarnings("FutureReturnValueIgnored")
  public void readDuringCreate() {
    // Loaded value and waiting at expireAfterCreate (expire: infinite)
    var initialValue = cache.get(key);
    assertThat(initialValue).isNotNull();

    await().untilTrue(startedLoad);
    doLoad.set(true);
    await().untilTrue(startedCreate);

    // Async read trying to wait at expireAfterRead
    var reader = CompletableFuture.runAsync(() -> {
      do {
        var value = cache.get(key);
        assertThat(value).isEqualTo(initialValue);
      } while (!endRead.get());
    }, executor);

    // Ran expireAfterCreate (expire: infinite -> create)
    doCreate.set(true);
    await().until(() -> policy.getExpiresAfter(key).orElseThrow().toNanos() <= EXPIRE_NS);
    await().untilTrue(startedRead);

    // Ran reader (expire: create -> ?)
    doRead.set(true);
    endRead.set(true);
    reader.join();

    // Ensure expire is [expireAfterCreate], not [infinite]
    assertThat(policy.getExpiresAfter(key).orElseThrow().toNanos()).isAtMost(EXPIRE_NS);
  }

  private AsyncLoadingCache<String, String> makeAsyncCache() {
    return Caffeine.newBuilder()
        .executor(executor)
        .expireAfter(new Expiry<String, String>() {
          @Override public long expireAfterCreate(String key, String value, long currentTime) {
            startedCreate.set(true);
            await().untilTrue(doCreate);
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
            startedRead.set(true);
            await().untilTrue(doRead);
            return currentDuration;
          }
        })
        .buildAsync(key -> {
          startedLoad.set(true);
          await().untilTrue(doLoad);
          return key + "'s value";
        });
  }
}
