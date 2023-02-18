/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.testing;

import static com.github.benmanes.caffeine.testing.CollectionSubject.collection;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertAbout;

import java.util.Map;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Ordered;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Additional propositions for {@link Map} subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class MapSubject extends com.google.common.truth.MapSubject {
  private final Map<?, ?> actual;

  public MapSubject(FailureMetadata metadata, Map<?, ?> subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  public static Factory<MapSubject, Map<?, ?>> map() {
    return MapSubject::new;
  }

  public static <K, V> MapSubject assertThat(Map<K, V> actual) {
    return assertAbout(map()).that(actual);
  }

  /** Fails if the map does not have the given size. */
  public final void hasSize(long expectedSize) {
    super.hasSize(Math.toIntExact(expectedSize));
  }

  /** Fails if the map does not have less than the given size. */
  public void hasSizeLessThan(long other) {
    checkArgument(other >= 0, "expectedSize (%s) must be >= 0", other);
    check("size()").that(actual.size()).isLessThan(Math.toIntExact(other));
  }

  /** Fails if the map's size is not in {@code range}. */
  public void hasSizeIn(Range<Integer> range) {
    check("size()").that(actual.size()).isIn(range);
  }

  /** Fails if the map does not contain the given keys, where duplicate keys are ignored. */
  @CanIgnoreReturnValue
  public Ordered containsExactlyKeys(Iterable<?> keys) {
    return check("containsKeys").that(actual.keySet())
        .containsExactlyElementsIn(ImmutableSet.copyOf(keys));
  }

  /** Fails if the map does not contain the given value. */
  public void containsValue(Object value) {
    check("containsValue").that(actual.values()).contains(value);
  }

  /** Fails if the map does contain the given value. */
  public void doesNotContainValue(Object value) {
    check("containsValue").that(actual.values()).doesNotContain(value);
  }

  /**
   * Fails if the map is not empty. This differs from {@link #isEmpty()} by checking all inspection
   * methods.
   */
  public void isExhaustivelyEmpty() {
    isEqualTo(Map.of());
    hasSize(0);
    isEmpty();

    check("isEmpty()").that(actual.isEmpty()).isTrue();
    check("toString()").that(actual.toString()).isEqualTo(Map.of().toString());
    check("hashCode()").that(actual.hashCode()).isEqualTo(Map.of().hashCode());
    check("keySet()").about(collection()).that(actual.keySet()).isExhaustivelyEmpty();
    check("values()").about(collection()).that(actual.values()).isExhaustivelyEmpty();
    check("entrySet()").about(collection()).that(actual.entrySet()).isExhaustivelyEmpty();
  }
}
