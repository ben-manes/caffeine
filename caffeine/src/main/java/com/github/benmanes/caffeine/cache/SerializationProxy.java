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

import static com.github.benmanes.caffeine.cache.Caffeine.UNSET_INT;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Serializes the configuration of the cache, reconsitituting it as a {@link Cache},
 * {@link LoadingCache}, or {@link AsyncLoadingCache} using {@link Caffeine} upon
 * deserialization. The data held by the cache is not retained.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class SerializationProxy<K, V> implements Serializable {
  private static final long serialVersionUID = 1;

  boolean async;
  boolean weakKeys;
  boolean weakValues;
  boolean softValues;
  boolean isRecordingStats;
  long refreshAfterWriteNanos;
  long expiresAfterWriteNanos;
  long expiresAfterAccessNanos;
  long maximumSize = UNSET_INT;
  long maximumWeight = UNSET_INT;

  @Nullable Ticker ticker;
  @Nullable Expiry<?, ?> expiry;
  @Nullable Weigher<?, ?> weigher;
  @Nullable AsyncCacheLoader<?, ?> loader;
  @Nullable RemovalListener<?, ?> removalListener;
  @Nullable RemovalListener<?, ?> evictionListener;

  @SuppressWarnings({"unchecked", "PreferJavaTimeOverload", "deprecation"})
  Caffeine<Object, Object> recreateCaffeine() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    if (ticker != null) {
      builder.ticker(ticker);
    }
    if (isRecordingStats) {
      builder.recordStats();
    }
    if (maximumSize != UNSET_INT) {
      builder.maximumSize(maximumSize);
    }
    if (weigher != null) {
      builder.maximumWeight(maximumWeight);
      builder.weigher((Weigher<Object, Object>) weigher);
    }
    if (expiry != null) {
      builder.expireAfter(expiry);
    }
    if (expiresAfterWriteNanos > 0) {
      builder.expireAfterWrite(expiresAfterWriteNanos, TimeUnit.NANOSECONDS);
    }
    if (expiresAfterAccessNanos > 0) {
      builder.expireAfterAccess(expiresAfterAccessNanos, TimeUnit.NANOSECONDS);
    }
    if (refreshAfterWriteNanos > 0) {
      builder.refreshAfterWrite(refreshAfterWriteNanos, TimeUnit.NANOSECONDS);
    }
    if (weakKeys) {
      builder.weakKeys();
    }
    if (weakValues) {
      builder.weakValues();
    }
    if (softValues) {
      builder.softValues();
    }
    if (removalListener != null) {
      builder.removalListener(removalListener);
    }
    if (evictionListener != null) {
      builder.evictionListener(evictionListener);
    }
    return builder;
  }

  Object readResolve() {
    Caffeine<Object, Object> builder = recreateCaffeine();
    if (async) {
      if (loader == null) {
        return builder.buildAsync();
      }
      @SuppressWarnings("unchecked")
      AsyncCacheLoader<K, V> cacheLoader = (AsyncCacheLoader<K, V>) loader;
      return builder.buildAsync(cacheLoader);
    }

    if (loader == null) {
      return builder.build();
    }
    @SuppressWarnings("unchecked")
    CacheLoader<K, V> cacheLoader = (CacheLoader<K, V>) loader;
    return builder.build(cacheLoader);
  }
}
