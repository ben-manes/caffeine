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
package com.github.benmanes.caffeine.cache.testing;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Reset;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;

/**
 * A factory that constructs a {@link Cache} from the {@link CacheContext}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PreferJavaTimeOverload")
public final class CaffeineCacheFromContext {
  interface SerializableTicker extends Ticker, Serializable {}

  private CaffeineCacheFromContext() {}

  public static <K, V> Cache<K, V> newCaffeineCache(CacheContext context) {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    context.caffeine = builder;

    if (context.initialCapacity != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity.size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximumSize != Maximum.DISABLED) {
      if (context.weigher == CacheWeigher.DEFAULT) {
        builder.maximumSize(context.maximumSize.max());
      } else {
        builder.weigher(context.weigher);
        builder.maximumWeight(context.maximumWeight());
      }
    }
    if (context.expiryType() != CacheExpiry.DISABLED) {
      builder.expireAfter(context.expiry);
    }
    if (context.afterAccess != Expire.DISABLED) {
      builder.expireAfterAccess(context.afterAccess.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.afterWrite != Expire.DISABLED) {
      builder.expireAfterWrite(context.afterWrite.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.refresh != Expire.DISABLED) {
      builder.refreshAfterWrite(context.refresh.timeNanos(), TimeUnit.NANOSECONDS);
    }
    if (context.expires() || context.refreshes()) {
      SerializableTicker ticker = context.ticker()::read;
      builder.ticker(ticker);
    }
    if (context.keyStrength == ReferenceType.WEAK) {
      builder.weakKeys();
    } else if (context.keyStrength == ReferenceType.SOFT) {
      throw new IllegalStateException();
    }
    if (context.isWeakValues()) {
      builder.weakValues();
    } else if (context.isSoftValues()) {
      builder.softValues();
    }
    if (context.cacheExecutor != CacheExecutor.DEFAULT) {
      builder.executor(context.executor);
    }
    if (context.cacheScheduler != CacheScheduler.DEFAULT) {
      builder.scheduler(context.scheduler);
    }
    if (context.removalListenerType != Listener.DEFAULT) {
      builder.removalListener(context.removalListener);
    }
    if (context.isStrongKeys() && !context.isAsync()) {
      builder.writer(context.cacheWriter());
    }
    if (context.isAsync()) {
      if (context.loader == null) {
        context.asyncCache = builder.buildAsync();
      } else {
        context.asyncCache = builder.buildAsync(
            context.isAsyncLoading ? context.loader.async() : context.loader);
      }
      context.cache = context.asyncCache.synchronous();
    } else if (context.loader == null) {
      context.cache = builder.build();
    } else {
      context.cache = builder.build(context.loader);
    }

    @SuppressWarnings("unchecked")
    Cache<K, V> castedCache = (Cache<K, V>) context.cache;
    Reset.resetThreadLocalRandom();
    return castedCache;
  }
}
