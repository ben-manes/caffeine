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

import java.util.concurrent.CompletableFuture;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedAsyncLocalLoadingCache;
import com.github.benmanes.caffeine.cache.Shared.AsyncLocalLoadingCache;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedAsyncLocalLoadingCache;

/**
 * A matcher that evaluates a {@link Cache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidCache<K, V>
    extends TypeSafeDiagnosingMatcher<Cache<K, V>> {
  Description description;

  @Override
  public void describeTo(Description description) {
    description.appendText("cache");
    if (this.description != description) {
      description.appendText(description.toString());
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  protected boolean matchesSafely(Cache<K, V> cache, Description description) {
    this.description = description;

    if (cache instanceof BoundedLocalCache.BoundedLocalManualCache<?, ?>) {
      BoundedLocalCache.BoundedLocalManualCache<K, V> local =
          (BoundedLocalCache.BoundedLocalManualCache<K, V>) cache;
      return IsValidBoundedLocalCache.<K, V>valid().matchesSafely(local.cache, description);
    } else if (cache instanceof AsyncLocalLoadingCache<?, ?, ?>.LoadingCacheView) {
      AsyncLocalLoadingCache<?, ?, ?> async =
          ((AsyncLocalLoadingCache<?, ?, ?>.LoadingCacheView) cache).getOuter();
      if (async instanceof BoundedAsyncLocalLoadingCache<?, ?>) {
        return IsValidBoundedLocalCache.<K, CompletableFuture<V>>valid().matchesSafely(
            ((BoundedAsyncLocalLoadingCache<K, V>) async).cache, description);
      }
    }

    if (cache instanceof UnboundedLocalCache.UnboundedLocalManualCache<?, ?>) {
      UnboundedLocalCache.UnboundedLocalManualCache<K, V> local =
          (UnboundedLocalCache.UnboundedLocalManualCache<K, V>) cache;
      return IsValidUnboundedLocalCache.<K, V>valid().matchesSafely(local.cache, description);
    } else if (cache instanceof AsyncLocalLoadingCache<?, ?, ?>.LoadingCacheView) {
      AsyncLocalLoadingCache<?, ?, ?> async =
          ((AsyncLocalLoadingCache<?, ?, ?>.LoadingCacheView) cache).getOuter();
      if (async instanceof UnboundedAsyncLocalLoadingCache<?, ?>) {
        return IsValidUnboundedLocalCache.<K, CompletableFuture<V>>valid().matchesSafely(
            ((UnboundedAsyncLocalLoadingCache<K, V>) async).cache, description);
      }

      UnboundedLocalCache.UnboundedAsyncLocalLoadingCache<K, V>.LoadingCacheView local =
          (UnboundedLocalCache.UnboundedAsyncLocalLoadingCache<K, V>.LoadingCacheView) cache;
      return IsValidUnboundedLocalCache.<K, CompletableFuture<V>>valid().matchesSafely(
          local.getOuter().cache, description);
    }

    return true;
  }

  @Factory
  public static <K, V> IsValidCache<K, V> validCache() {
    return new IsValidCache<K, V>();
  }
}
