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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.Caffeine.AsyncWeigher;
import com.github.benmanes.caffeine.cache.Caffeine.BoundedWeigher;
import com.github.benmanes.caffeine.matchers.DescriptionBuilder;
import com.google.common.testing.SerializableTester;

/**
 * A matcher that evaluates a cache by creating a serialized copy and checking its equality.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsCacheReserializable<T> extends TypeSafeDiagnosingMatcher<T> {

  private IsCacheReserializable() {}

  @Override
  public void describeTo(Description description) {
    description.appendValue("serialized copy");
  }

  @Override
  public boolean matchesSafely(T original, Description description) {
    DescriptionBuilder desc = new DescriptionBuilder(description);

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
      checkSyncronousCache(syncCache, syncCopy, desc);
    } else {
      throw new UnsupportedOperationException();
    }

    return desc.matches();
  }

  private static <K, V> void checkAsynchronousCache(AsyncLoadingCache<K, V> original,
      AsyncLoadingCache<K, V> copy, DescriptionBuilder desc) {
    if (original instanceof UnboundedLocalCache.LocalAsyncLoadingCache<?, ?>) {
      checkUnboundedLocalAsyncLoadingCache(
          (UnboundedLocalCache.LocalAsyncLoadingCache<K, V>) original,
          (UnboundedLocalCache.LocalAsyncLoadingCache<K, V>) copy, desc);
    }
  }

  private static <K, V> void checkUnboundedLocalAsyncLoadingCache(
      UnboundedLocalCache.LocalAsyncLoadingCache<K, V> original,
      UnboundedLocalCache.LocalAsyncLoadingCache<K, V> copy, DescriptionBuilder desc) {
    checkUnboundedLocalCache(original.cache, copy.cache, desc);
    desc.expectThat("same cacheLoader", copy.loader, is(original.loader));

  }

  private static <K, V> void checkSyncronousCache(Cache<K, V> original, Cache<K, V> copy,
      DescriptionBuilder desc) {
    if (!IsValidCache.<K, V>validCache().matchesSafely(copy, desc.getDescription())) {
      desc.expected("valid cache");
      return;
    }
    desc.expectThat("empty", copy.estimatedSize(), is(0L));

    if (original instanceof UnboundedLocalCache.LocalAsyncLoadingCache<?, ?>.LoadingCacheView) {
      checkUnboundedLocalAsyncLoadingCache(
          ((UnboundedLocalCache.LocalAsyncLoadingCache<K, V>.LoadingCacheView) original).getOuter(),
          ((UnboundedLocalCache.LocalAsyncLoadingCache<K, V>.LoadingCacheView) copy).getOuter(),
          desc);
    }
    if (original instanceof BoundedLocalCache.LocalManualCache<?, ?>) {
      checkBoundedLocalManualCache((BoundedLocalCache.LocalManualCache<K, V>) original,
          (BoundedLocalCache.LocalManualCache<K, V>) copy, desc);
    }
    if (original instanceof BoundedLocalCache.LocalLoadingCache<?, ?>) {
      checkBoundedLocalLoadingCache((BoundedLocalCache.LocalLoadingCache<K, V>) original,
          (BoundedLocalCache.LocalLoadingCache<K, V>) copy, desc);
    }
    if (original instanceof UnboundedLocalCache.LocalLoadingCache<?, ?>) {
      checkUnoundedLocalManualCache((UnboundedLocalCache.LocalManualCache<K, V>) original,
          (UnboundedLocalCache.LocalManualCache<K, V>) copy, desc);
    }
    if (original instanceof UnboundedLocalCache.LocalLoadingCache<?, ?>) {
      checkUnboundedLocalLoadingCache((UnboundedLocalCache.LocalLoadingCache<K, V>) original,
          (UnboundedLocalCache.LocalLoadingCache<K, V>) copy, desc);
    }
  }

  private static <K, V> void checkBoundedLocalManualCache(
      BoundedLocalCache.LocalManualCache<K, V> original,
      BoundedLocalCache.LocalManualCache<K, V> copy, DescriptionBuilder desc) {
    if (original.cache.weigher instanceof BoundedWeigher<?, ?>) {
      desc.expectThat("same weigher",
          unwrapWeigher(copy.cache.weigher).getClass(), is(equalTo(
          unwrapWeigher(original.cache.weigher).getClass())));
    } else {
      desc.expectThat("same weigher", copy.cache.weigher, is(original.cache.weigher));
    }
    desc.expectThat("same keyStrategy", copy.cache.keyStrategy, is(original.cache.keyStrategy));
    desc.expectThat("same valueStrategy",
        copy.cache.valueStrategy, is(original.cache.valueStrategy));
    if (copy.cache.maximumWeightedSize == null) {
      desc.expectThat("null maximumWeight", copy.cache.maximumWeightedSize, is(nullValue()));
    } else {
      desc.expectThat("same maximumWeight",
          copy.cache.maximumWeightedSize.get(), is(original.cache.maximumWeightedSize.get()));
    }

    desc.expectThat("same expireAfterWriteNanos",
        copy.cache.expireAfterWriteNanos, is(original.cache.expireAfterWriteNanos));
    desc.expectThat("same expireAfterWriteNanos",
        copy.cache.expireAfterWriteNanos, is(original.cache.expireAfterWriteNanos));
    desc.expectThat("same expireAfterWriteNanos",
        copy.cache.expireAfterWriteNanos, is(original.cache.expireAfterWriteNanos));

    if (original.cache.removalListener == null) {
      desc.expectThat("same removalListener", copy.cache.removalListener, is(nullValue()));
    } else if (copy.cache.removalListener == null) {
      desc.expected("non-null removalListener");
    } else if (copy.cache.removalListener.getClass() != original.cache.removalListener.getClass()) {
      desc.expected("same removalListener but was " + copy.cache.removalListener.getClass());
    }
  }

  private static <K, V> Weigher<K, V> unwrapWeigher(Weigher<?, ?> weigher) {
    for (;;) {
      if (weigher instanceof BoundedWeigher<?, ?>) {
        weigher = ((BoundedWeigher<?, ?>) weigher).delegate;
      } else if (weigher instanceof AsyncWeigher<?, ?>) {
        weigher = ((AsyncWeigher<?, ?>) weigher).delegate;
      } else {
        @SuppressWarnings("unchecked")
        Weigher<K, V> castedWeigher = (Weigher<K, V>) weigher;
        return castedWeigher;
      }
    }
  }

  private static <K, V> void checkBoundedLocalLoadingCache(
      BoundedLocalCache.LocalLoadingCache<K, V> original,
      BoundedLocalCache.LocalLoadingCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("same cacheLoader", copy.cache.loader, is(original.cache.loader));
  }

  private static <K, V> void checkUnoundedLocalManualCache(
      UnboundedLocalCache.LocalManualCache<K, V> original,
      UnboundedLocalCache.LocalManualCache<K, V> copy, DescriptionBuilder desc) {
    checkUnboundedLocalCache(original.cache, copy.cache, desc);
  }

  private static <K, V> void checkUnboundedLocalCache(UnboundedLocalCache<K, V> original,
      UnboundedLocalCache<K, V> copy, DescriptionBuilder desc) {
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

  private static <K, V> void checkUnboundedLocalLoadingCache(
      UnboundedLocalCache.LocalLoadingCache<K, V> original,
      UnboundedLocalCache.LocalLoadingCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("same cacheLoader", copy.loader, is(original.loader));
  }

  @Factory
  public static <T> Matcher<T> reserializable() {
    return new IsCacheReserializable<T>();
  }
}
