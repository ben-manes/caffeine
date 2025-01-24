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

import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.testing.FakeTicker;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFutureTask;

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
  private final String testKey = Issue193Test.class.getSimpleName();
  private final AtomicLong counter = new AtomicLong(0);
  private final FakeTicker ticker = new FakeTicker();

  private @Nullable ListenableFutureTask<Long> loadingTask;

  private final AsyncCacheLoader<String, Long> loader = (key, executor) -> {
    // Fools the cache into thinking there is a future that's not immediately ready.
    // (The Cache has optimizations for this that we want to avoid)
    loadingTask = ListenableFutureTask.create(counter::getAndIncrement);
    var f = new CompletableFuture<Long>();
    loadingTask.addListener(() -> {
      f.complete(Futures.getUnchecked(requireNonNull(loadingTask)));
    }, executor);
    return f;
  };

  /** This ensures that any outstanding async loading is completed as well */
  private long loadGet(AsyncLoadingCache<String, Long> cache, String key) {
    CompletableFuture<Long> future = cache.get(key);
    requireNonNull(loadingTask);
    if (!loadingTask.isDone()) {
      loadingTask.run();
    }
    return future.join();
  }

  @Test
  public void invalidateDuringRefreshRemovalCheck() {
    var removed = new ArrayList<Long>();
    AsyncLoadingCache<String, Long> cache = Caffeine.newBuilder()
        .removalListener(
            (@Nullable String key, @Nullable Long value, RemovalCause reason) -> removed.add(value))
        .refreshAfterWrite(Duration.ofNanos(10))
        .executor(Runnable::run)
        .ticker(ticker::read)
        .buildAsync(loader);

    // Load so there is an initial value.
    assertThat(loadGet(cache, testKey)).isEqualTo(0);

    ticker.advance(Duration.ofNanos(11)); // Refresh should fire on next access
    assertThat(cache).containsEntry(testKey, 0); // Old value

    cache.synchronous().invalidate(testKey); // Invalidate key entirely
    assertThat(cache).doesNotContainKey(testKey); // No value in cache (good)
    requireNonNull(loadingTask).run(); // Completes refresh

    assertThat(cache).doesNotContainKey(testKey); // Value in cache (bad)
    assertThat(removed).containsExactly(0L, 1L).inOrder(); // 1L was sent to removalListener anyway
  }
}
