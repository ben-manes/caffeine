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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

/**
 * A matcher that evaluates a {@link Cache#asMap()} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidMapView<K, V> extends TypeSafeDiagnosingMatcher<Map<K, V>> {
  Description description;

  @Override
  public void describeTo(Description description) {
    description.appendText("cache");
    if (this.description != description) {
      description.appendText(this.description.toString());
    }
  }

  @Override
  protected boolean matchesSafely(Map<K, V> map, Description description) {
    this.description = description;

    if (map instanceof BoundedLocalCache<?, ?>) {
      BoundedLocalCache<K, V> cache = (BoundedLocalCache<K, V>) map;
      return IsValidBoundedLocalCache.<K, V>valid().matchesSafely(cache, description);
    } else if (map instanceof UnboundedLocalCache<?, ?>) {
      UnboundedLocalCache<K, V> cache = (UnboundedLocalCache<K, V>) map;
      return IsValidUnboundedLocalCache.<K, V>valid().matchesSafely(cache, description);
    } else if (map instanceof LocalAsyncLoadingCache.AsMapView<?, ?>) {
      LocalAsyncLoadingCache.AsMapView<K, V> asMap = (LocalAsyncLoadingCache.AsMapView<K, V>) map;
      if (asMap.delegate instanceof BoundedLocalCache<?, ?>) {
        return IsValidBoundedLocalCache.<K, CompletableFuture<V>>valid().matchesSafely(
            (BoundedLocalCache<K, CompletableFuture<V>>) asMap.delegate, description);
      } else if (asMap.delegate instanceof UnboundedLocalCache<?, ?>) {
        return IsValidUnboundedLocalCache.<K, CompletableFuture<V>>valid().matchesSafely(
            (UnboundedLocalCache<K, CompletableFuture<V>>) asMap.delegate, description);
      }
    }

    return true;
  }

  public static <K, V> IsValidMapView<K, V> validAsMap() {
    return new IsValidMapView<>();
  }
}
