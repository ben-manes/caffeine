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
import org.hamcrest.TypeSafeDiagnosingMatcher;

/**
 * A matcher that evaluates a {@link AsyncLoadingCache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidAsyncCache<K, V>
    extends TypeSafeDiagnosingMatcher<AsyncLoadingCache<K, V>> {
  Description description;

  @Override
  public void describeTo(Description description) {
    description.appendText("async cache");
    if (this.description != description) {
      description.appendText(this.description.toString());
    }
  }

  @Override
  protected boolean matchesSafely(AsyncLoadingCache<K, V> cache, Description description) {
    this.description = description;

    if (cache instanceof BoundedLocalCache.BoundedLocalAsyncLoadingCache<?, ?>) {
      BoundedLocalCache.BoundedLocalAsyncLoadingCache<K, V> local =
          (BoundedLocalCache.BoundedLocalAsyncLoadingCache<K, V>) cache;
      return IsValidBoundedLocalCache.<K, CompletableFuture<V>>valid()
          .matchesSafely(local.cache, description);
    }

    if (cache instanceof UnboundedLocalCache.UnboundedLocalAsyncLoadingCache<?, ?>) {
      UnboundedLocalCache.UnboundedLocalAsyncLoadingCache<K, V> local =
          (UnboundedLocalCache.UnboundedLocalAsyncLoadingCache<K, V>) cache;
      return IsValidUnboundedLocalCache.<K, CompletableFuture<V>>valid()
          .matchesSafely(local.cache, description);
    }

    return true;
  }

  public static <K, V> IsValidAsyncCache<K, V> validAsyncCache() {
    return new IsValidAsyncCache<>();
  }
}
