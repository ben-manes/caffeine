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

import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedWeigher;
import com.github.benmanes.caffeine.matchers.DescriptionBuilder;
import com.google.common.testing.SerializableTester;

/**
 * A matcher that evaluates a cache by creating a serialized copy and checking its equality.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsCacheReserializable<K, V> extends TypeSafeDiagnosingMatcher<Cache<K, V>> {

  @Override
  public void describeTo(Description description) {
    description.appendValue("serialized copy");
  }

  @Override
  public boolean matchesSafely(Cache<K, V> cache, Description description) {
    DescriptionBuilder desc = new DescriptionBuilder(description);

    Cache<K, V> copy = SerializableTester.reserialize(cache);
    if (!IsValidCache.<K, V>validCache().matchesSafely(copy, description)) {
      return false;
    }
    desc.expectThat("empty", copy.estimatedSize(), is(0L));

    if (cache instanceof BoundedLocalCache.LocalManualCache<?, ?>) {
      checkBoundedLocalManualCache((BoundedLocalCache.LocalManualCache<K, V>) cache,
          (BoundedLocalCache.LocalManualCache<K, V>) copy, desc);
    }
    if (cache instanceof BoundedLocalCache.LocalLoadingCache<?, ?>) {
      checkBoundedLocalLoadingCache((BoundedLocalCache.LocalLoadingCache<K, V>) cache,
          (BoundedLocalCache.LocalLoadingCache<K, V>) copy, desc);
    }
    if (cache instanceof UnboundedLocalCache.LocalLoadingCache<?, ?>) {
      checkUnoundedLocalManualCache((UnboundedLocalCache.LocalManualCache<K, V>) cache,
          (UnboundedLocalCache.LocalManualCache<K, V>) copy, desc);
    }
    if (cache instanceof UnboundedLocalCache.LocalLoadingCache<?, ?>) {
      checkUnboundedLocalLoadingCache((UnboundedLocalCache.LocalLoadingCache<K, V>) cache,
          (UnboundedLocalCache.LocalLoadingCache<K, V>) copy, desc);
    }

    return desc.matches();
  }

  private void checkBoundedLocalManualCache(BoundedLocalCache.LocalManualCache<K, V> original,
      BoundedLocalCache.LocalManualCache<K, V> copy, DescriptionBuilder desc) {
    if (original.cache.weigher instanceof BoundedWeigher<?, ?>) {
      desc.expectThat("same weigher",
          ((BoundedWeigher<?, ?>) copy.cache.weigher).weigher.getClass(),
          equalTo((((BoundedWeigher<?, ?>) original.cache.weigher).weigher.getClass())));
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

  private void checkBoundedLocalLoadingCache(BoundedLocalCache.LocalLoadingCache<K, V> original,
      BoundedLocalCache.LocalLoadingCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("same cacheLoader", copy.cache.loader, is(original.cache.loader));
  }

  private void checkUnoundedLocalManualCache(UnboundedLocalCache.LocalManualCache<K, V> original,
      UnboundedLocalCache.LocalManualCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("same ticker", copy.cache.ticker, is(original.cache.ticker));
    desc.expectThat("same isRecordingStats",
        copy.cache.isRecordingStats, is(original.cache.isRecordingStats));
    if (original.cache.removalListener == null) {
      desc.expectThat("same removalListener", copy.cache.removalListener, is(nullValue()));
    } else if (copy.cache.removalListener == null) {
      desc.expected("non-null removalListener");
    } else if (copy.cache.removalListener.getClass() != original.cache.removalListener.getClass()) {
      desc.expected("same removalListener but was " + copy.cache.removalListener.getClass());
    }
  }

  private void checkUnboundedLocalLoadingCache(UnboundedLocalCache.LocalLoadingCache<K, V> original,
      UnboundedLocalCache.LocalLoadingCache<K, V> copy, DescriptionBuilder desc) {
    desc.expectThat("same cacheLoader", copy.loader, is(original.loader));
  }

  @Factory
  public static <K, V> Matcher<Cache<K, V>> reserializable() {
    return new IsCacheReserializable<K, V>();
  }
}
