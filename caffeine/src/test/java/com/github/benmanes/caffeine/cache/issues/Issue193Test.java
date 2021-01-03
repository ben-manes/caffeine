/*
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.testing.FakeTicker;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.testing.TestingExecutors;

/**
 * Issue #193: Invalidate before Refresh completes still stores value
 * <p>
 * When a refresh starts before an invalidate and completes afterwards, the entry is inserted into
 * the cache. This breaks linearizability assumptions, as the invalidation may be to ensure that
 * the cache does not hold stale data that refresh will have observed in its load. This undesirable
 * behavior is also present in Guava, so the stricter handling is an intentional deviation.
 *
 * @author boschb (Robert Bosch)
 */
public final class Issue193Test {
  private final AtomicLong counter = new AtomicLong(0);
  private final FakeTicker ticker = new FakeTicker();

  private ListenableFutureTask<Long> loadingTask;

  private final AsyncCacheLoader<String, Long> loader =
      (key, exec) -> {
          // Fools the cache into thinking there is a future that's not immediately ready.
          // (The Cache has optimizations for this that we want to avoid)
        loadingTask = ListenableFutureTask.create(counter::getAndIncrement);
        var f = new CompletableFuture<Long>();
        loadingTask.addListener(() -> {
          f.complete(Futures.getUnchecked(loadingTask));
        }, exec);
        return f;
      };

  private final String key = Issue193Test.class.getSimpleName();

  /** This ensures that any outstanding async loading is completed as well */
  private long loadGet(AsyncLoadingCache<String, Long> cache, String key)
      throws InterruptedException, ExecutionException {
    CompletableFuture<Long> future = cache.get(key);
    if (!loadingTask.isDone()) {
      loadingTask.run();
    }
    return future.get();
  }

  @Test
  public void invalidateDuringRefreshRemovalCheck() throws Exception {
    List<Long> removed = new ArrayList<>();
    AsyncLoadingCache<String, Long> cache =
        Caffeine.newBuilder()
            .ticker(ticker::read)
            .executor(TestingExecutors.sameThreadScheduledExecutor())
            .<String, Long>removalListener((key, value, reason) -> removed.add(value))
            .refreshAfterWrite(10, TimeUnit.NANOSECONDS)
            .buildAsync(loader);

    // Load so there is an initial value.
    assertThat(loadGet(cache, key), is(0L));

    ticker.advance(11); // Refresh should fire on next access
    assertThat(cache.synchronous().getIfPresent(key), is(0L)); // Old value

    cache.synchronous().invalidate(key); // Invalidate key entirely
    assertThat(cache.synchronous().getIfPresent(key), is(nullValue())); // No value in cache (good)
    loadingTask.run(); // Completes refresh

    // FIXME: java.lang.AssertionError: Not true that <1> is null
    assertThat(cache.synchronous().getIfPresent(key), is(nullValue())); // Value in cache (bad)

    // FIXME: Maybe?  This is what I wanted to actually test :)
    assertThat(removed, is(List.of(0L, 1L))); // 1L was sent to removalListener anyways
  }
}
