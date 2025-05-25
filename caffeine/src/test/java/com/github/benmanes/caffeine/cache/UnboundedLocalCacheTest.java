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

import static com.github.benmanes.caffeine.cache.UnboundedLocalCache.findVarHandle;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.slf4j.event.Level.TRACE;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;

/**
 * The test cases for the implementation details of {@link UnboundedLocalCache}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(TRACE)
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class UnboundedLocalCacheTest {

  @CacheSpec(population = Population.EMPTY, refreshAfterWrite = Expire.DISABLED,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  @Test(dataProvider = "caches")
  public void noPolicy(Cache<Integer, Integer> cache, CacheContext context) {
    assertThat(cache.policy().eviction()).isEmpty();
    assertThat(cache.policy().expireAfterWrite()).isEmpty();
    assertThat(cache.policy().expireAfterAccess()).isEmpty();
    assertThat(cache.policy().refreshAfterWrite()).isEmpty();
  }

  @Test
  @SuppressWarnings("PMD.AvoidCatchingNPE")
  public void refreshes_memoize() {
    // The refresh map is never unset once initialized and a CAS race can cause a thread's attempt
    // at initialization to fail so it re-reads for the current value. This asserts a non-null value
    // for NullAway's static analysis. We can test the failed CAS scenario by resetting the field
    // and catching the NullPointerException, thereby proving that the failed initialization falls
    // back to a re-read. This error will not happen in practice since the field is not modified
    // again.
    var signal = new CompletableFuture<@Nullable Void>().orTimeout(10, TimeUnit.SECONDS);
    var cache = new UnboundedLocalCache<>(Caffeine.newBuilder(), /* isAsync= */ false);
    ConcurrentTestHarness.timeTasks(10, () -> {
      while (!signal.isDone()) {
        try {
          cache.refreshes = null;
          assertThat(cache.refreshes()).isNotNull();
        } catch (NullPointerException e) {
          signal.complete(null);
          return;
        }
      }
    });
    assertThat(signal.join()).isNull();
  }

  @Test
  public void findVarHandle_absent() {
    assertThrows(ExceptionInInitializerError.class, () ->
        findVarHandle(UnboundedLocalCache.class, "absent", int.class));
  }
}
