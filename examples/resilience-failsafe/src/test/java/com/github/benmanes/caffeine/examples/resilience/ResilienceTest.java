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
package com.github.benmanes.caffeine.examples.resilience;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.failsafe.Failsafe;
import dev.failsafe.Fallback;
import dev.failsafe.RetryPolicy;
import dev.failsafe.Timeout;
import dev.failsafe.TimeoutExceededException;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ResilienceTest {

  /*
   * Synchronous:
   * ------------
   * A synchronous cache performs the load while holding the hash table's lock, which may block
   * writes to other entries within the same hash bin. To avoid long hold times, prefer performing
   * the retry strategy outside of the cache load.
   */

  @Test
  public void retry_sync() {
    // Given the resilience policy
    Cache<Integer, String> cache = Caffeine.newBuilder().build();
    var retryPolicy = RetryPolicy.builder()
        .onFailedAttempt(event -> System.err.println("Failure #" + event.getAttemptCount()))
        .withJitter(Duration.ofMillis(250))
        .withDelay(Duration.ofSeconds(1))
        .withMaxAttempts(3)
        .build();
    var failsafe = Failsafe.with(retryPolicy);

    // When the work is performed with intermittent failures
    var result = failsafe.get(context -> {
      return cache.get(1, key -> {
        if (context.getAttemptCount() < (retryPolicy.getConfig().getMaxAttempts() - 1)) {
          throw new IllegalStateException("failed");
        }
        return "success";
      });
    });

    // Then complete successfully
    assertThat(result).isEqualTo("success");
    System.out.println("Completed with " + result);
  }

  @Test
  public void fallback_sync() {
    // Given the resilience policy
    Cache<Integer, String> cache = Caffeine.newBuilder().build();
    var retryPolicy = RetryPolicy.<String>builder()
        .onFailedAttempt(event -> System.err.println("Failure #" + event.getAttemptCount()))
        .withMaxAttempts(3)
        .build();
    var fallback = Fallback.of("fallback");
    var failsafe = Failsafe.with(fallback, retryPolicy);

    // When the work fails
    var result = failsafe.get(context ->
        cache.get(1, key -> { throw new IllegalStateException("failed"); }));

    // Then complete with the fallback
    assertThat(result).isEqualTo("fallback");
    System.out.println("Completed with " + result);
  }

  @Test
  public void timeout_sync() {
    // Given the resilience policy
    Cache<Integer, String> cache = Caffeine.newBuilder().build();
    var retryPolicy = RetryPolicy.builder()
        .onFailedAttempt(event -> System.err.println("Failure #" + event.getAttemptCount()))
        .withMaxAttempts(3)
        .build();
    var timeout = Timeout.builder(Duration.ofSeconds(1)).withInterrupt().build();
    var failsafe = Failsafe.with(timeout, retryPolicy);

    try {
      // When the work exceeds a threshold
      failsafe.get(context -> cache.get(1, key -> {
        try {
          TimeUnit.SECONDS.sleep(10);
          return null;
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }));
      fail("Should have timed out");
    } catch (TimeoutExceededException e) {
      // Then complete with a timeout
      System.out.println("Completed with timeout");
    }
  }

  /*
   * Asynchronous:
   * ------------
   * An asynchronous cache performs the load within the future's lock after the mapping has been
   * established. The retry strategy can be performed by the future task without penalizing other
   * writes, allowing the policy to be placed closer to where the problems might occur.
   */

  @Test
  public void retry_async() {
    // Given the resilience policy
    AsyncCache<Integer, String> cache = Caffeine.newBuilder().buildAsync();
    var result = cache.get(1, (key, executor) -> {
      var retryPolicy = RetryPolicy.builder()
          .onFailedAttempt(event -> System.err.println("Failure #" + event.getAttemptCount()))
          .withJitter(Duration.ofMillis(250))
          .withDelay(Duration.ofSeconds(1))
          .withMaxAttempts(3)
          .build();
      var failsafe = Failsafe.with(retryPolicy);

      // When the work is performed with intermittent failures
      return failsafe.getAsync(context -> {
        if (context.getAttemptCount() < (retryPolicy.getConfig().getMaxAttempts() - 1)) {
          throw new IllegalStateException("failed");
        }
        return "success";
      });
    });

    // Then complete successfully
    assertThat(result.join()).isEqualTo("success");
    System.out.println("Completed with " + result.join());
  }

  @Test
  public void fallback_async() {
    AsyncCache<Integer, String> cache = Caffeine.newBuilder().buildAsync();
    var result = cache.get(1, (key, executor) -> {
      // Given the resilience policy
      var retryPolicy = RetryPolicy.<String>builder()
          .onFailedAttempt(event -> System.err.println("Failure #" + event.getAttemptCount()))
          .withMaxAttempts(3)
          .build();
      var fallback = Fallback.of("fallback");
      var failsafe = Failsafe.with(fallback, retryPolicy);

      // When the work fails
      return failsafe.getAsync(context -> { throw new IllegalStateException("failed"); });
    });

    // Then complete with the fallback
    assertThat(result.join()).isEqualTo("fallback");
    System.out.println("Completed with " + result.join());
  }

  @Test
  public void timeout_async() {
    // Given the resilience policy
    AsyncCache<Integer, String> cache = Caffeine.newBuilder().buildAsync();
    var result = cache.get(1, (key, executor) ->
        new CompletableFuture<String>().orTimeout(1, TimeUnit.SECONDS));

    try {
      // When the work fails
      result.join();
      fail("Should have timed out");
    } catch (CompletionException e) {
      // Then complete with a timeout
      assertThat(e).hasCauseThat().isInstanceOf(TimeoutException.class);
      System.out.println("Completed with timeout");
    }
  }
}
