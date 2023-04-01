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
import java.time.Duration;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Serializes the configuration of the cache, reconstituting it as a {@link Cache},
 * {@link LoadingCache}, {@link AsyncCache}, or {@link AsyncLoadingCache} using {@link Caffeine}
 * upon deserialization. The data held by the cache is not retained.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("serial")
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
  @Nullable AsyncCacheLoader<?, ?> cacheLoader;
  @Nullable RemovalListener<?, ?> removalListener;
  @Nullable RemovalListener<?, ?> evictionListener;

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
      @SuppressWarnings("unchecked")
      var castedWeigher = (Weigher<Object, Object>) weigher;
      builder.maximumWeight(maximumWeight);
      builder.weigher(castedWeigher);
    }
    if (expiry != null) {
      builder.expireAfter(expiry);
    }
    if (expiresAfterWriteNanos > 0) {
      builder.expireAfterWrite(Duration.ofNanos(expiresAfterWriteNanos));
    }
    if (expiresAfterAccessNanos > 0) {
      builder.expireAfterAccess(Duration.ofNanos(expiresAfterAccessNanos));
    }
    if (refreshAfterWriteNanos > 0) {
      builder.refreshAfterWrite(Duration.ofNanos(refreshAfterWriteNanos));
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
      if (cacheLoader == null) {
        return builder.buildAsync();
      }
      @SuppressWarnings("unchecked")
      AsyncCacheLoader<K, V> loader = (AsyncCacheLoader<K, V>) cacheLoader;
      return builder.buildAsync(loader);
    }

    if (cacheLoader == null) {
      return builder.build();
    }
    @SuppressWarnings("unchecked")
    CacheLoader<K, V> loader = (CacheLoader<K, V>) cacheLoader;
    return builder.build(loader);
  }
}
