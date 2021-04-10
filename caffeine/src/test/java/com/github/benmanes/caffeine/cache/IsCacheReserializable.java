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
package com.github.benmanes.caffeine.cache;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.Async.AsyncEvictionListener;
import com.github.benmanes.caffeine.cache.Async.AsyncExpiry;
import com.github.benmanes.caffeine.cache.Async.AsyncWeigher;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedLocalAsyncLoadingCache;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedLocalLoadingCache;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedLocalManualCache;
import com.github.benmanes.caffeine.cache.LocalAsyncLoadingCache.LoadingCacheView;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalAsyncLoadingCache;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalLoadingCache;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalManualCache;
import com.github.benmanes.caffeine.testing.DescriptionBuilder;
import com.google.common.testing.SerializableTester;

/**
 * A matcher that evaluates a cache by creating a serialized copy and checking its equality.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsCacheReserializable<T> extends TypeSafeDiagnosingMatcher<T> {
  DescriptionBuilder desc;

  private IsCacheReserializable() {}

  @Override
  public void describeTo(Description description) {
    description.appendValue("serialized copy");
    if (desc.getDescription() != description) {
      description.appendText(desc.getDescription().toString());
    }
  }

  @Override
  public boolean matchesSafely(T original, Description description) {
    desc = new DescriptionBuilder(description);

    T copy = SerializableTester.reserialize(original);

    if (original instanceof AsyncLoadingCache<?, ?>) {
      @SuppressWarnings("unchecked")
      AsyncLoadingCache<Object, Object> asyncCache = (AsyncLoadingCache<Object, Object>) original;
      @SuppressWarnings("unchecked")
      AsyncLoadingCache<Object, Object> asyncCopy = (AsyncLoadingCache<Object, Object>) copy;
      checkAsynchronousCache(asyncCache, asyncCopy, desc);
    } else if (original instanceof Cache<?, ?>) {
      @SuppressWarnings("unchecked")
      Cache<Object, Object> syncCache = (Cache<Object, Object>) original;
      @SuppressWarnings("unchecked")
      Cache<Object, Object> syncCopy = (Cache<Object, Object>) copy;
      checkSynchronousCache(syncCache, syncCopy, desc);
    } else {
      throw new UnsupportedOperationException();
    }

    return desc.matches();
  }

  private static <K, V> void checkAsynchronousCache(AsyncLoadingCache<K, V> original,
      AsyncLoadingCache<K, V> copy, DescriptionBuilder desc) {
    if (!IsValidAsyncCache.<K, V>validAsyncCache().matchesSafely(copy, desc.getDescription())) {
      desc.expected("valid async cache");
    } else if (original instanceof UnboundedLocalAsyncLoadingCache<?, ?>) {
      checkUnboundedAsyncLocalLoadingCache(
          (UnboundedLocalAsyncLoadingCache<K, V>) original,
          (UnboundedLocalAsyncLoadingCache<K, V>) copy, desc);
    } else if (original instanceof BoundedLocalAsyncLoadingCache<?, ?>) {
      checkBoundedAsyncLocalLoadingCache(
          (BoundedLocalAsyncLoadingCache<K, V>) original,
          (BoundedLocalAsyncLoadingCache<K, V>) copy, desc);
    }
  }

  private static <K, V> void checkSynchronousCache(Cache<K, V> original, Cache<K, V> copy,
      DescriptionBuilder desc) {
    if (!IsValidCache.<K, V>validCache().matchesSafely(copy, desc.getDescription())) {
      desc.expected("valid cache");
      return;
    }

    checkIfUnbounded(original, copy, desc);
    checkIfBounded(original, copy, desc);
  }

  /* --------------- Unbounded --------------- */

  @SuppressWarnings("unchecked")
  private static <K, V> void checkIfUnbounded(
      Cache<K, V> original, Cache<K, V> copy, DescriptionBuilder desc) {
    if (original instanceof UnboundedLocalManualCache<?, ?>) {
      checkUnboundedLocalManualCache(
          (UnboundedLocalManualCache<K, V>) original,
          (UnboundedLocalManualCache<K, V>) copy, desc);
    }
    if (original instanceof UnboundedLocalLoadingCache<?, ?>) {
      checkUnboundedLocalLoadingCache(
          (UnboundedLocalLoadingCache<K, V>) original,
          (UnboundedLocalLoadingCache<K, V>) copy, desc);
    }
    if (original instanceof LoadingCacheView<?, ?>) {
      LocalAsyncLoadingCache<?, ?> originalAsync = ((LoadingCacheView<?, ?>) original).asyncCache();
      LocalAsyncLoadingCache<?, ?> copyAsync = ((LoadingCacheView<?, ?>) copy).asyncCache();
      if (originalAsync instanceof UnboundedLocalAsyncLoadingCache<?, ?>) {
        checkUnboundedAsyncLocalLoadingCache(
            (UnboundedLocalAsyncLoadingCache<K, V>) originalAsync,
            (UnboundedLocalAsyncLoadingCache<K, V>) copyAsync, desc);
      }
    }
  }

  private static <K, V> void checkUnboundedLocalManualCache(
      UnboundedLocalManualCache<K, V> original,
      UnboundedLocalManualCache<K, V> copy, DescriptionBuilder desc) {
    checkUnboundedLocalCache(original.cache, copy.cache, desc);
  }

  private static <K, V> void checkUnboundedLocalLoadingCache(
      UnboundedLocalLoadingCache<K, V> original,
      UnboundedLocalLoadingCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("same cacheLoader", copy.loader, is(original.loader));
  }

  private static <K, V> void checkUnboundedAsyncLocalLoadingCache(
      UnboundedLocalAsyncLoadingCache<K, V> original,
      UnboundedLocalAsyncLoadingCache<K, V> copy, DescriptionBuilder desc) {
    checkUnboundedLocalCache(original.cache, copy.cache, desc);
    desc.expectThat("same cacheLoader", copy.loader, is(original.loader));
  }

  private static <K, V> void checkUnboundedLocalCache(UnboundedLocalCache<K, V> original,
      UnboundedLocalCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("estimated empty", copy.estimatedSize(), is(0L));
    desc.expectThat("same ticker", copy.ticker, is(original.ticker));
    desc.expectThat("same isRecordingStats",
        copy.isRecordingStats, is(original.isRecordingStats));

    if (original.removalListener == null) {
      desc.expectThat("same removalListener", copy.removalListener, is(nullValue()));
    } else if (copy.removalListener == null) {
      desc.expected("non-null removalListener");
    } else if (copy.removalListener.getClass() != original.removalListener.getClass()) {
      desc.expected("same removalListener but was " + copy.removalListener.getClass());
    }
  }

  /* --------------- Bounded --------------- */

  @SuppressWarnings("unchecked")
  private static <K, V> void checkIfBounded(
      Cache<K, V> original, Cache<K, V> copy, DescriptionBuilder desc) {
    if (original instanceof BoundedLocalManualCache<?, ?>) {
      checkBoundedLocalManualCache(
          (BoundedLocalManualCache<K, V>) original,
          (BoundedLocalManualCache<K, V>) copy, desc);
    }
    if (original instanceof BoundedLocalLoadingCache<?, ?>) {
      checkBoundedLocalLoadingCache(
          (BoundedLocalLoadingCache<K, V>) original,
          (BoundedLocalLoadingCache<K, V>) copy, desc);
    }
    if (original instanceof LoadingCacheView) {
      LocalAsyncLoadingCache<?, ?> originalAsync = ((LoadingCacheView<K, V>) original).asyncCache();
      LocalAsyncLoadingCache<?, ?> copyAsync = ((LoadingCacheView<K, V>) copy).asyncCache();
      if (originalAsync instanceof BoundedLocalAsyncLoadingCache<?, ?>) {
        checkBoundedAsyncLocalLoadingCache(
            (BoundedLocalAsyncLoadingCache<K, V>) originalAsync,
            (BoundedLocalAsyncLoadingCache<K, V>) copyAsync, desc);
      }
    }
  }

  private static <K, V> void checkBoundedLocalManualCache(BoundedLocalManualCache<K, V> original,
      BoundedLocalManualCache<K, V> copy, DescriptionBuilder desc) {
    checkBoundedLocalCache(original.cache, copy.cache, desc);
  }

  private static <K, V> void checkBoundedLocalLoadingCache(BoundedLocalLoadingCache<K, V> original,
      BoundedLocalLoadingCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("same cacheLoader", copy.cache.cacheLoader, is(original.cache.cacheLoader));
  }

  private static <K, V> void checkBoundedAsyncLocalLoadingCache(
      BoundedLocalAsyncLoadingCache<K, V> original,
      BoundedLocalAsyncLoadingCache<K, V> copy, DescriptionBuilder desc) {
    checkBoundedLocalCache(original.cache, copy.cache, desc);
    desc.expectThat("same cacheLoader", copy.loader, is(original.loader));
  }

  private static <K, V> void checkBoundedLocalCache(BoundedLocalCache<K, V> original,
      BoundedLocalCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("empty", copy.estimatedSize(), is(0L));
    desc.expectThat("same weigher", unwrapWeigher(copy.weigher).getClass(),
        is(equalTo(unwrapWeigher(original.weigher).getClass())));
    desc.expectThat("same nodeFactory",
        copy.nodeFactory, instanceOf(original.nodeFactory.getClass()));
    if (original.evicts()) {
      desc.expectThat("same maximumWeight", copy.maximum(), is(original.maximum()));
      desc.expectThat("same maximumwindowWeight",
          copy.windowMaximum(), is(original.windowMaximum()));
    }

    if (original.expiresVariable()) {
      desc.expectThat("same expiry", unwrapExpiry(copy.expiry()).getClass(),
          is(equalTo(unwrapExpiry(original.expiry()).getClass())));
    } else {
      desc.expectThat("", copy.expiresVariable(), is(false));
    }

    if (original.expiresAfterAccess()) {
      desc.expectThat("same expiresAfterAccessNanos",
          copy.expiresAfterAccessNanos(), is(original.expiresAfterAccessNanos()));
    } else {
      desc.expectThat("", copy.expiresAfterAccess(), is(false));
    }

    if (original.expiresAfterWrite()) {
      desc.expectThat("same expireAfterWriteNanos",
          copy.expiresAfterWriteNanos(), is(original.expiresAfterWriteNanos()));
    } else {
      desc.expectThat("", copy.expiresAfterWrite(), is(false));
    }

    if (original.refreshAfterWrite()) {
      desc.expectThat("same refreshAfterWriteNanos",
          copy.refreshAfterWriteNanos(), is(original.refreshAfterWriteNanos()));
    } else {
      desc.expectThat("", copy.refreshAfterWrite(), is(false));
    }

    if (original.evictionListener == null) {
      desc.expectThat("same evictionListener", copy.evictionListener, is(nullValue()));
    } else if (copy.evictionListener == null) {
      desc.expected("non-null evictionListener");
    } else if (original.evictionListener instanceof AsyncEvictionListener<?, ?>) {
      var copyListener = ((AsyncEvictionListener<?, ?>) copy.evictionListener).delegate;
      var originalListener = ((AsyncEvictionListener<?, ?>) original.evictionListener).delegate;
      desc.expectThat("same evictionListener",
          copyListener.getClass(), is(originalListener.getClass()));
    } else if (copy.evictionListener.getClass() != original.evictionListener.getClass()) {
      desc.expected("same removalListener but was " + copy.removalListener().getClass());
    }

    if (original.removalListener() == null) {
      desc.expectThat("same removalListener", copy.removalListener(), is(nullValue()));
    } else if (copy.removalListener() == null) {
      desc.expected("non-null removalListener");
    } else if (copy.removalListener().getClass() != original.removalListener().getClass()) {
      desc.expected("same removalListener but was " + copy.removalListener().getClass());
    }
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Weigher<K, V> unwrapWeigher(Weigher<K, V> weigher) {
    for (;;) {
      if (weigher instanceof BoundedWeigher<?, ?>) {
        weigher = (Weigher<K, V>) ((BoundedWeigher<?, ?>) weigher).delegate;
      } else if (weigher instanceof AsyncWeigher<?, ?>) {
        weigher = (Weigher<K, V>) ((AsyncWeigher<?, ?>) weigher).delegate;
      } else {
        return weigher;
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Expiry<K, V> unwrapExpiry(Expiry<K, V> expiry) {
    for (;;) {
      if (expiry instanceof AsyncExpiry<?, ?>) {
        expiry = (Expiry<K, V>) ((AsyncExpiry<?, ?>) expiry).delegate;
      } else {
        return expiry;
      }
    }
  }

  public static <T> Matcher<T> reserializable() {
    return new IsCacheReserializable<T>();
  }
}
