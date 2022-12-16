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

import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.asyncCache;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.cache;

import com.github.benmanes.caffeine.cache.Async.AsyncEvictionListener;
import com.github.benmanes.caffeine.cache.Async.AsyncExpiry;
import com.github.benmanes.caffeine.cache.Async.AsyncRemovalListener;
import com.github.benmanes.caffeine.cache.Async.AsyncWeigher;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedLocalAsyncLoadingCache;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedLocalManualCache;
import com.github.benmanes.caffeine.cache.LocalAsyncCache.AbstractCacheView;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalAsyncLoadingCache;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalManualCache;
import com.google.common.testing.SerializableTester;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;

/**
 * A subject that validates a serialized copy of a cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ReserializableSubject extends Subject {
  private final Object actual;

  private ReserializableSubject(FailureMetadata metadata, Object subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  public static Factory<ReserializableSubject, AsyncCache<?, ?>> asyncReserializable() {
    return ReserializableSubject::new;
  }

  public static Factory<ReserializableSubject, Cache<?, ?>> syncReserializable() {
    return ReserializableSubject::new;
  }

  public void isReserialize() {
    var reserialized = SerializableTester.reserialize(actual);
    checkIfAsyncCache(reserialized);
    checkIfSyncCache(reserialized);
  }

  private void checkIfSyncCache(Object reserialized) {
    if (actual instanceof Cache<?, ?>) {
      var copy = (Cache<?, ?>) reserialized;
      check("valid").about(cache()).that(copy).isValid();
      check("empty").about(cache()).that(copy).isEmpty();
    }
    if (actual instanceof LocalLoadingCache<?, ?>) {
      var original = (LocalLoadingCache<?, ?>) actual;
      var copy = (LocalLoadingCache<?, ?>) reserialized;
      check("cacheLoader").that(copy.cacheLoader()).isEqualTo(original.cacheLoader());
    }
    if (actual instanceof BoundedLocalManualCache<?, ?>) {
      var original = (BoundedLocalManualCache<?, ?>) actual;
      var copy = (BoundedLocalManualCache<?, ?>) reserialized;
      checkBoundedLocalCache(original.cache, copy.cache);
    }
    if (actual instanceof UnboundedLocalManualCache<?, ?>) {
      var original = (UnboundedLocalManualCache<?, ?>) actual;
      var copy = (UnboundedLocalManualCache<?, ?>) reserialized;
      checkUnboundedLocalCache(original.cache, copy.cache);
    }
  }

  private void checkIfAsyncCache(Object reserialized) {
    if (actual instanceof AsyncCache<?, ?>) {
      var copy = (AsyncCache<?, ?>) reserialized;
      check("valid").about(asyncCache()).that(copy).isValid();
      check("empty").about(asyncCache()).that(copy).isEmpty();
    }
    if (actual instanceof LocalAsyncLoadingCache<?, ?>) {
      var original = (LocalAsyncLoadingCache<?, ?>) actual;
      var copy = (LocalAsyncLoadingCache<?, ?>) reserialized;
      check("cacheLoader").that(copy.cacheLoader).isEqualTo(original.cacheLoader);
    }
    if (actual instanceof AbstractCacheView<?, ?>) {
      var original = ((AbstractCacheView<?, ?>) actual).asyncCache();
      var copy = ((AbstractCacheView<?, ?>) reserialized).asyncCache();
      if (original instanceof BoundedLocalAsyncLoadingCache<?, ?>) {
        checkBoundedAsyncLocalLoadingCache(
            (BoundedLocalAsyncLoadingCache<?, ?>) original,
            (BoundedLocalAsyncLoadingCache<?, ?>) copy);
      } else if (original instanceof UnboundedLocalAsyncLoadingCache<?, ?>) {
        checkUnboundedAsyncLocalLoadingCache(
            (UnboundedLocalAsyncLoadingCache<?, ?>) original,
            (UnboundedLocalAsyncLoadingCache<?, ?>) copy);
      }
    }
    if (actual instanceof BoundedLocalAsyncLoadingCache<?, ?>) {
      var original = (BoundedLocalAsyncLoadingCache<?, ?>) actual;
      var copy = (BoundedLocalAsyncLoadingCache<?, ?>) reserialized;
      checkBoundedLocalCache(original.cache, copy.cache);
    }
    if (actual instanceof UnboundedLocalAsyncLoadingCache<?, ?>) {
      var original = (UnboundedLocalAsyncLoadingCache<?, ?>) actual;
      var copy = (UnboundedLocalAsyncLoadingCache<?, ?>) reserialized;
      checkUnboundedLocalCache(original.cache, copy.cache);
    }
  }

  /* --------------- Bounded --------------- */

  private void checkBoundedAsyncLocalLoadingCache(
      BoundedLocalAsyncLoadingCache<?, ?> original,
      BoundedLocalAsyncLoadingCache<?, ?> copy) {
    check("cacheLoader").that(copy.cacheLoader).isEqualTo(original.cacheLoader);
    checkBoundedLocalCache(original.cache, copy.cache);
  }

  private void checkBoundedLocalCache(
      BoundedLocalCache<?, ?> original, BoundedLocalCache<?, ?> copy) {
    check("nodeFactory").that(copy.nodeFactory).isInstanceOf(original.nodeFactory.getClass());
    checkEviction(original, copy);
    checkExpiresAfterAccess(original, copy);
    checkExpiresAfterWrite(original, copy);
    checkExpiresVariable(original, copy);
    checkRefreshAfterWrite(original, copy);
    checkRemovalListener(original, copy);
    checkEvictionListener(original, copy);
  }

  private void checkRefreshAfterWrite(
      BoundedLocalCache<?, ?> original, BoundedLocalCache<?, ?> copy) {
    check("refreshAfterWrite()").that(copy.refreshAfterWrite())
        .isEqualTo(original.refreshAfterWrite());
    if (original.refreshAfterWrite()) {
      check("refreshAfterWriteNanos()").that(copy.refreshAfterWriteNanos())
          .isEqualTo(original.refreshAfterWriteNanos());
    }
  }

  private void checkEviction(
      BoundedLocalCache<?, ?> original, BoundedLocalCache<?, ?> copy) {
    check("evicts()").that(copy.evicts()).isEqualTo(original.evicts());
    check("weigher()").that(unwrapWeigher(copy.weigher))
        .isInstanceOf(unwrapWeigher(original.weigher).getClass());
    if (original.evicts()) {
      check("maximum()").that(copy.maximum()).isEqualTo(original.maximum());
    }
  }

  private void checkExpiresAfterAccess(
      BoundedLocalCache<?, ?> original, BoundedLocalCache<?, ?> copy) {
    check("expiresAfterAccess()").that(copy.expiresAfterAccess())
        .isEqualTo(original.expiresAfterAccess());
    if (original.expiresAfterAccess()) {
      check("expiresAfterAccessNanos()").that(copy.expiresAfterAccessNanos())
          .isEqualTo(original.expiresAfterAccessNanos());
    }
  }

  private void checkExpiresAfterWrite(
      BoundedLocalCache<?, ?> original, BoundedLocalCache<?, ?> copy) {
    check("expiresAfterWrite()").that(copy.expiresAfterWrite())
        .isEqualTo(original.expiresAfterWrite());
    if (original.expiresAfterWrite()) {
      check("expiresAfterWriteNanos()").that(copy.expiresAfterWriteNanos())
          .isEqualTo(original.expiresAfterWriteNanos());
    }
  }

  private void checkExpiresVariable(
      BoundedLocalCache<?, ?> original, BoundedLocalCache<?, ?> copy) {
    check("expiresVariable()").that(copy.expiresVariable())
        .isEqualTo(original.expiresVariable());
    if (original.expiresVariable()) {
      check("expiry()").that(unwrapExpiry(copy.expiry()))
          .isInstanceOf(unwrapExpiry(original.expiry()).getClass());
    }
  }

  private void checkRemovalListener(
      BoundedLocalCache<?, ?> original, BoundedLocalCache<?, ?> copy) {
    if (original.removalListener() == null) {
      check("removalListener()").that(copy.removalListener()).isNull();
    } else if (copy.removalListener() == null) {
      check("removalListener()").that(copy.removalListener()).isNotNull();
    } else if (original.evictionListener instanceof AsyncRemovalListener<?, ?>) {
      var source = ((AsyncRemovalListener<?, ?>) original.removalListener()).delegate;
      var target = ((AsyncRemovalListener<?, ?>) copy.removalListener()).delegate;
      check("removalListener()").that(target).isInstanceOf(source.getClass());
    } else {
      check("removalListener()").that(copy.removalListener())
          .isInstanceOf(original.removalListener().getClass());
    }
  }

  private void checkEvictionListener(
      BoundedLocalCache<?, ?> original, BoundedLocalCache<?, ?> copy) {
    if (original.evictionListener == null) {
      check("evictionListener").that(copy.evictionListener).isNull();
    } else if (copy.evictionListener == null) {
      check("evictionListener").that(copy.evictionListener).isNotNull();
    } else if (original.evictionListener instanceof AsyncEvictionListener<?, ?>) {
      var source = ((AsyncEvictionListener<?, ?>) original.evictionListener).delegate;
      var target = ((AsyncEvictionListener<?, ?>) copy.evictionListener).delegate;
      check("evictionListener").that(target).isInstanceOf(source.getClass());
    } else {
      check("evictionListener").that(copy.evictionListener)
          .isInstanceOf(original.evictionListener.getClass());
    }
  }

  private Weigher<?, ?> unwrapWeigher(Weigher<?, ?> weigher) {
    for (;;) {
      if (weigher instanceof BoundedWeigher<?, ?>) {
        weigher = ((BoundedWeigher<?, ?>) weigher).delegate;
      } else if (weigher instanceof AsyncWeigher<?, ?>) {
        weigher = ((AsyncWeigher<?, ?>) weigher).delegate;
      } else {
        return weigher;
      }
    }
  }

  private Expiry<?, ?> unwrapExpiry(Expiry<?, ?> expiry) {
    for (;;) {
      if (expiry instanceof AsyncExpiry<?, ?>) {
        expiry = ((AsyncExpiry<?, ?>) expiry).delegate;
      } else {
        return expiry;
      }
    }
  }

  /* --------------- Unbounded --------------- */

  private void checkUnboundedAsyncLocalLoadingCache(
      UnboundedLocalAsyncLoadingCache<?, ?> original,
      UnboundedLocalAsyncLoadingCache<?, ?> copy) {
    check("cacheLoader").that(copy.cacheLoader).isEqualTo(original.cacheLoader);
    checkUnboundedLocalCache(original.cache, copy.cache);
  }

  private void checkUnboundedLocalCache(
      UnboundedLocalCache<?, ?> original, UnboundedLocalCache<?, ?> copy) {
    check("ticker").that(copy.ticker).isEqualTo(original.ticker);
    check("isRecordingStats").that(copy.isRecordingStats).isEqualTo(original.isRecordingStats);

    if (original.removalListener == null) {
      check("removalListener").that(copy.removalListener).isNull();
    } else if (copy.removalListener.getClass() != original.removalListener.getClass()) {
      check("removalListener").that(copy.removalListener).isNotNull();
      check("removalListenerClass").that(copy.removalListener)
          .isInstanceOf(original.removalListener.getClass());
    }
  }
}
