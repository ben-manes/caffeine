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

import static com.github.benmanes.caffeine.cache.LocalCacheSubject.asyncLocal;
import static com.github.benmanes.caffeine.cache.ReserializableSubject.asyncReserializable;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.cache;
import static com.github.benmanes.caffeine.testing.MapSubject.map;
import static com.google.common.truth.Truth.assertAbout;

import java.util.Map;
import java.util.concurrent.Future;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;

/**
 * Propositions for {@link AsyncCache} subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AsyncCacheSubject extends Subject {
  private final AsyncCache<?, ?> actual;

  private AsyncCacheSubject(FailureMetadata metadata, AsyncCache<?, ?> subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  public static Factory<AsyncCacheSubject, AsyncCache<?, ?>> asyncCache() {
    return AsyncCacheSubject::new;
  }

  public static AsyncCacheSubject assertThat(AsyncCache<?, ?> actual) {
    return assertAbout(asyncCache()).that(actual);
  }

  /** Fails if the cache is not empty. */
  public void isEmpty() {
    check("cache").about(map()).that(actual.asMap()).isExhaustivelyEmpty();
    check("cache").about(cache()).that(actual.synchronous()).isEmpty();
  }

  /** Fails if the cache does not have the given size. */
  public void hasSize(long expectedSize) {
    check("estimatedSize()").about(cache()).that(actual.synchronous()).hasSize(expectedSize);
  }

  /** Fails if the cache does not have less than the given size. */
  public void hasSizeLessThan(long other) {
    check("estimatedSize()").about(cache()).that(actual.synchronous()).hasSizeLessThan(other);
  }

  /** Fails if the cache does not have more than the given size. */
  public void hasSizeGreaterThan(long other) {
    check("estimatedSize()").about(cache()).that(actual.synchronous()).hasSizeGreaterThan(other);
  }

  /** Fails if the cache does not contain the given key. */
  public void containsKey(Object key) {
    check("cache").that(actual.asMap()).containsKey(key);
  }

  /** Fails if the map contains the given key. */
  public void doesNotContainKey(Object key) {
    check("cache").that(actual.asMap()).doesNotContainKey(key);
  }

  /** Fails if the cache does not contain the given value. */
  public void containsValue(Object value) {
    if (value instanceof Future<?>) {
      check("cache").about(map()).that(actual.asMap()).containsValue(value);
    } else {
      check("cache").about(cache()).that(actual.synchronous()).containsValue(value);
    }
  }

  /** Fails if the cache does not contain the given entry. */
  public void containsEntry(Object key, Object value) {
    if (value instanceof Future<?>) {
      check("cache").that(actual.asMap()).containsEntry(key, value);
    } else {
      check("cache").about(cache()).that(actual.synchronous()).containsEntry(key, value);
    }
  }

  /** Fails if the cache does not contain exactly the given set of entries in the given map. */
  public void containsExactlyEntriesIn(Map<?, ?> expectedMap) {
    if (expectedMap.values().stream().anyMatch(value -> value instanceof Future<?>)) {
      check("cache").that(actual.asMap()).containsExactlyEntriesIn(expectedMap);
    } else {
      check("cache").about(cache())
          .that(actual.synchronous()).containsExactlyEntriesIn(expectedMap);
    }
  }

  /** Fails if the cache is not correctly serialized. */
  public void isReserialize() {
    check("reserializable").about(asyncReserializable()).that(actual).isReserialize();
  }

  /** Fails if the cache is in an inconsistent state. */
  public void isValid() {
    check("cache").about(asyncLocal()).that(actual).isValid();
  }
}
