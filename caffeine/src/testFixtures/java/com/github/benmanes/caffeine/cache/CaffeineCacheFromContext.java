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

import java.io.Serializable;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheScheduler;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;

/**
 * A factory that constructs a {@link Cache} from the {@link CacheContext}.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineCacheFromContext {
  @FunctionalInterface
  interface SerializableTicker extends Ticker, Serializable {}

  private CaffeineCacheFromContext() {}

  public static <K, V> Cache<K, V> newCaffeineCache(CacheContext context) {
    var builder = Caffeine.newBuilder();
    context.with(builder);

    if (context.initialCapacity() != InitialCapacity.DEFAULT) {
      builder.initialCapacity(context.initialCapacity().size());
    }
    if (context.isRecordingStats()) {
      builder.recordStats();
    }
    if (context.maximum() != Maximum.DISABLED) {
      if (context.cacheWeigher() == CacheWeigher.DISABLED) {
        builder.maximumSize(context.maximum().max());
      } else {
        builder.weigher(context.weigher());
        builder.maximumWeight(context.maximumWeight());
      }
    }
    if (context.expiryType() != CacheExpiry.DISABLED) {
      builder.expireAfter(context.expiry());
    }
    if (context.expiresAfterAccess()) {
      builder.expireAfterAccess(context.expireAfterAccess().duration());
    }
    if (context.expiresAfterWrite()) {
      builder.expireAfterWrite(context.expireAfterWrite().duration());
    }
    if (context.refreshes()) {
      builder.refreshAfterWrite(context.refreshAfterWrite().duration());
    }
    if (context.expires() || context.refreshes()) {
      SerializableTicker ticker = context.ticker()::read;
      builder.ticker(ticker);
    }
    if (context.isWeakKeys()) {
      builder.weakKeys();
    } else if (!context.isStrongKeys()) {
      throw new IllegalStateException();
    }
    if (context.isWeakValues()) {
      builder.weakValues();
    } else if (context.isSoftValues()) {
      builder.softValues();
    }
    if (context.executorType() != CacheExecutor.DEFAULT) {
      builder.executor(context.executor());
    }
    if (context.schedulerType() != CacheScheduler.DISABLED) {
      builder.scheduler(context.scheduler());
    }
    if (context.removalListenerType() != Listener.DISABLED) {
      builder.removalListener(context.removalListener());
    }
    if (context.evictionListenerType() != Listener.DISABLED) {
      builder.evictionListener(context.evictionListener());
    }
    if (context.isAsync()) {
      if (context.loader() == Loader.DISABLED) {
        context.with(builder.buildAsync());
      } else {
        context.with(builder.buildAsync(
            context.isAsyncLoader() ? context.loader().async() : context.loader()));
      }
      context.with(context.asyncCache().synchronous());
    } else if (context.loader() == Loader.DISABLED) {
      context.with(builder.build());
    } else {
      context.with(builder.build(context.loader()));
    }

    @SuppressWarnings("unchecked")
    var castedCache = (Cache<K, V>) context.cache();
    return castedCache;
  }
}
