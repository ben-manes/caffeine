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
package com.github.benmanes.caffeine.cache.issues;

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.FutureSubject.future;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.time.ZoneOffset.UTC;
import static java.util.Locale.US;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Isolated;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncCacheSubject;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.testing.FutureSubject;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Issue #30: Unexpected cache misses with <code>expireAfterWrite</code> using multiple keys.
 * <p>
 * Prior to eviction, the cache must revalidate that the entry has expired. If the entry was updated
 * but the maintenance thread reads a stale value, then the entry may be prematurely expired. The
 * removal must detect that the entry was "resurrected" and cancel the expiration.
 *
 * @author yurgis2
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Isolated
@TestInstance(PER_CLASS)
final class Issue30Test {
  private static final boolean DEBUG = false;

  private static final String A_KEY = "a_key";
  private static final String A_ORIGINAL = "a_original";
  private static final String A_UPDATE_1 = "a_update_1";
  private static final String A_UPDATE_2 = "a_update_2";

  private static final String B_KEY = "b_key";
  private static final String B_ORIGINAL = "b_original";
  private static final String B_UPDATE_1 = "b_update_1";
  private static final String B_UPDATE_2 = "b_update_2";

  private static final int TTL = 100;
  private static final int EPSILON = 10;
  private static final int N_THREADS = 10;

  @AutoClose("shutdown")
  @SuppressFBWarnings("HES_EXECUTOR_NEVER_SHUTDOWN")
  private final ExecutorService executor = Executors.newFixedThreadPool(N_THREADS);

  @RepeatedTest(100)
  void expiration() throws InterruptedException {
    var source = new ConcurrentHashMap<String, String>();
    var lastLoad = new ConcurrentHashMap<String, Instant>();
    AsyncLoadingCache<String, String> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMillis(TTL))
        .executor(executor)
        .buildAsync(new Loader(source, lastLoad));
    initialValues(cache, source, lastLoad);
    firstUpdate(cache, source);
    secondUpdate(cache, source);
    AsyncCacheSubject.assertThat(cache).isValid();
  }

  private static void initialValues(AsyncLoadingCache<String, String> cache,
      ConcurrentMap<String, String> source, ConcurrentMap<String, Instant> lastLoad) {
    source.put(A_KEY, A_ORIGINAL);
    source.put(B_KEY, B_ORIGINAL);
    lastLoad.clear();

    assertThat("should serve initial value", cache.get(A_KEY)).succeedsWith(A_ORIGINAL);
    assertThat("should serve initial value", cache.get(B_KEY)).succeedsWith(B_ORIGINAL);
  }

  @SuppressWarnings("PreferJavaTimeOverload")
  private static void firstUpdate(AsyncLoadingCache<String, String> cache,
      ConcurrentMap<String, String> source) throws InterruptedException {
    source.put(A_KEY, A_UPDATE_1);
    source.put(B_KEY, B_UPDATE_1);

    assertThat("should serve cached initial value", cache.get(A_KEY)).succeedsWith(A_ORIGINAL);
    assertThat("should serve cached initial value", cache.get(B_KEY)).succeedsWith(B_ORIGINAL);

    Thread.sleep(EPSILON); // sleep for less than expiration
    assertThat("still serve cached initial value", cache.get(A_KEY)).succeedsWith(A_ORIGINAL);
    assertThat("still serve cached initial value", cache.get(B_KEY)).succeedsWith(B_ORIGINAL);

    Thread.sleep(TTL + EPSILON); // sleep until expiration
    assertThat("now serve first updated value", cache.get(A_KEY)).succeedsWith(A_UPDATE_1);
    await().untilAsserted(() -> {
      assertThat("now serve first updated value", cache.get(B_KEY)).succeedsWith(B_UPDATE_1);
    });
  }

  @SuppressWarnings("PreferJavaTimeOverload")
  private static void secondUpdate(AsyncLoadingCache<String, String> cache,
      ConcurrentMap<String, String> source) throws InterruptedException {
    source.put(A_KEY, A_UPDATE_2);
    source.put(B_KEY, B_UPDATE_2);

    assertThat("serve cached first updated value", cache.get(A_KEY)).succeedsWith(A_UPDATE_1);
    assertThat("serve cached first updated value", cache.get(B_KEY)).succeedsWith(B_UPDATE_1);

    Thread.sleep(EPSILON); // sleep for less than expiration
    assertThat("serve cached first updated value", cache.get(A_KEY)).succeedsWith(A_UPDATE_1);
    assertThat("serve cached first updated value", cache.get(A_KEY)).succeedsWith(A_UPDATE_1);
  }

  private static FutureSubject assertThat(String message, CompletableFuture<?> actual) {
    return assertWithMessage(message).about(future()).that(actual);
  }

  private static final class Loader implements AsyncCacheLoader<String, String> {
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS", US);

    final ConcurrentMap<String, String> source;
    final ConcurrentMap<String, Instant> lastLoad;

    Loader(ConcurrentMap<String, String> source, ConcurrentMap<String, Instant> lastLoad) {
      this.source = source;
      this.lastLoad = lastLoad;
    }

    @Override
    public CompletableFuture<String> asyncLoad(String key, Executor executor) {
      reportCacheMiss(key);
      return CompletableFuture.completedFuture(source.get(key));
    }

    @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE")
    @SuppressWarnings({"SystemOut", "TimeZoneUsage"})
    private void reportCacheMiss(String key) {
      Instant now = Instant.now();
      Instant last = lastLoad.get(key);
      lastLoad.put(key, now);

      if (DEBUG) {
        String time = FORMATTER.format(LocalDateTime.now(UTC));
        if (last == null) {
          System.out.println(key + ": first load @ " + time);
        } else {
          long duration = (now.toEpochMilli() - last.toEpochMilli());
          System.out.println(key + ": " + duration + "ms after last load @ " + time);
        }
      }
    }
  }
}
