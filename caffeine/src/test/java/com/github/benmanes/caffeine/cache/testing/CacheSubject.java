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

import static com.github.benmanes.caffeine.cache.LocalCacheSubject.syncLocal;
import static com.github.benmanes.caffeine.cache.ReserializableSubject.syncReserializable;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.CleanUpSubject.CLEANUP_FACTORY;
import static com.github.benmanes.caffeine.testing.MapSubject.map;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertAbout;

import java.util.Map;
import java.util.Objects;

import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.testing.GcFinalization;
import com.google.common.truth.Correspondence;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Ordered;
import com.google.common.truth.Subject;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Propositions for {@link Cache} subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheSubject extends Subject {
  private static final Correspondence<Object, Object> EQUALITY =
      Correspondence.from(CacheSubject::tolerantEquals, "is equal to");

  private final Cache<?, ?> actual;

  CacheSubject(FailureMetadata metadata, Cache<?, ?> subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  public static Factory<CacheSubject, Cache<?, ?>> cache() {
    return CacheSubject::new;
  }

  public static CacheSubject assertThat(Cache<?, ?> actual) {
    return assertAbout(cache()).that(actual);
  }

  /** Fails if the cache is not empty. */
  public void isEmpty() {
    check("cache").about(map()).that(actual.asMap()).isExhaustivelyEmpty();
    hasSize(0);

    actual.policy().eviction().ifPresent(policy -> {
      policy.weightedSize().ifPresent(weightedSize -> {
        check("weightedSize()").that(weightedSize).isEqualTo(0);
      });
    });
  }

  /** Fails if the cache does not have the given size. */
  public void hasSize(long expectedSize) {
    checkArgument(expectedSize >= 0, "expectedSize (%s) must be >= 0", expectedSize);
    check("estimatedSize()").that(actual.estimatedSize()).isEqualTo(expectedSize);
  }

  /** Fails if the cache does not have less than the given size. */
  public void hasSizeLessThan(long other) {
    checkArgument(other >= 0, "expectedSize (%s) must be >= 0", other);
    check("estimatedSize()").that(actual.estimatedSize()).isLessThan(other);
  }

  /** Fails if the cache does not have more than the given size. */
  public void hasSizeGreaterThan(long other) {
    checkArgument(other >= 0, "expectedSize (%s) must be >= 0", other);
    check("estimatedSize()").that(actual.estimatedSize()).isGreaterThan(other);
  }

  /** Fails if the cache does not contain the given key. */
  public void containsKey(Object key) {
    check("cache").that(actual.asMap()).containsKey(key);
  }

  /** Fails if the cache does not contain the given keys, where duplicate keys are ignored. */
  @CanIgnoreReturnValue
  public Ordered containsExactlyKeys(Iterable<?> keys) {
    return check("containsKeys").about(map()).that(actual.asMap()).containsExactlyKeys(keys);
  }

  /** Fails if the map contains the given key. */
  public void doesNotContainKey(Object key) {
    check("cache").that(actual.asMap()).doesNotContainKey(key);
  }

  /** Fails if the cache does not contain the given value. */
  public void containsValue(Object value) {
    check("cache").about(map()).that(actual.asMap()).containsValue(value);
  }

  /** Fails if the cache does contain the given value. */
  public void doesNotContainValue(Object value) {
    check("cache").about(map()).that(actual.asMap()).doesNotContainValue(value);
  }

  /** Fails if the cache does not contain the given entry. */
  public void containsEntry(Object key, Object value) {
    check("cache").that(actual.asMap())
        .comparingValuesUsing(EQUALITY)
        .containsEntry(key, value);
  }

  /** Fails if the cache contains the given entry. */
  public final void doesNotContainEntry(Object key, Object value) {
    check("cache").that(actual.asMap())
        .comparingValuesUsing(EQUALITY)
        .doesNotContainEntry(key, value);
  }

  /** Fails if the cache does not contain exactly the given set of entries in the given map. */
  public void containsExactlyEntriesIn(Map<?, ?> expectedMap) {
    check("cache").that(actual.asMap())
        .comparingValuesUsing(EQUALITY)
        .containsExactlyEntriesIn(expectedMap);
  }

  /** Fails if the cache is not correctly serialized. */
  public void isReserialize() {
    check("reserializable").about(syncReserializable()).that(actual).isReserialize();
  }

  /** Fails if the cache is in an inconsistent state. */
  public void isValid() {
    check("cache").about(syncLocal()).that(actual).isValid();
  }

  /** Propositions for the cache's size after fully cleaned up. */
  public CleanUpSubject whenCleanedUp() {
    return check("cleanUp()").about(CLEANUP_FACTORY).that(actual);
  }

  private static boolean tolerantEquals(Object o1, Object o2) {
    if ((o1 instanceof Integer) && (o2 instanceof Long)) {
      return ((Integer) o1).longValue() == ((Long) o2).longValue();
    } else if ((o1 instanceof Long) && (o2 instanceof Integer)) {
      return ((Long) o1).longValue() == ((Integer) o2).longValue();
    }
    return Objects.equals(o1, o2);
  }

  public static final class CleanUpSubject extends Subject {
    static final Factory<CleanUpSubject, Cache<?, ?>> CLEANUP_FACTORY = CleanUpSubject::new;

    private final Cache<?, ?> actual;

    private CleanUpSubject(FailureMetadata metadata, Cache<?, ?> cache) {
      super(metadata, cache.asMap());
      this.actual = cache;
    }

    public void isEmpty() {
      hasSize(0);
      check("cache").about(cache()).that(actual).isEmpty();
    }

    public void hasSize(long expectedSize) {
      // Ensures that all of the pending work is performed (Guava limits work per cycle)
      for (int i = 0; i < 100; i++) {
        if ((i > 0) && ((i % 10) == 0)) {
          GcFinalization.awaitFullGc();
        }
        actual.cleanUp();

        long size = actual.estimatedSize();
        if (size == expectedSize) {
          return;
        } else if (size < expectedSize) {
          failWithActual("After cleanup expected size to be at least", expectedSize);
        }
      }
      check("cache").about(cache()).that(actual).hasSize(expectedSize);
    }
  }
}
