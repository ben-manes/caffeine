/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.google.common.collect.ImmutableSet;

/**
 * The cache configuration context for a test case.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheContext {
  Listener removalListenerType;
  @Nullable RemovalListener<Integer, Integer> removalListener;

  InitialCapacity initialCapacity;
  MaximumSize maximumSize;
  Population population;
  Executor executor;
  Stats stats;

  ReferenceType keyStrength;
  ReferenceType valueStrength;

  boolean isLoading;
  Cache<Integer, Integer> cache;
  Map<Integer, Integer> original;

  @Nullable Integer firstKey;
  @Nullable Integer middleKey;
  @Nullable Integer lastKey;

  // Generated on-demand
  Integer absentKey;
  Set<Integer> absentKeys;

  public CacheContext() {
    removalListenerType = Listener.DEFAULT;
    original = new LinkedHashMap<>();
  }

  public Integer firstKey() {
    assertThat("Invalid usage of context", firstKey, is(not(nullValue())));
    return firstKey;
  }

  public Integer middleKey() {
    assertThat("Invalid usage of context", middleKey, is(not(nullValue())));
    return middleKey;
  }

  public Integer lastKey() {
    assertThat("Invalid usage of context", lastKey, is(not(nullValue())));
    return lastKey;
  }

  public Set<Integer> firstMiddleLastKeys() {
    return ImmutableSet.of(firstKey, middleKey, lastKey);
  }

  public Integer absentKey() {
    return (absentKey == null) ? (absentKey = nextAbsentKey()) : absentKey;
  }

  public Set<Integer> absentKeys() {
    if (absentKeys != null) {
      return absentKeys;
    }
    Set<Integer> absent = new HashSet<>();
    do {
      absent.add(nextAbsentKey());
    } while (absent.size() < 10);
    return absent;
  }

  private Integer nextAbsentKey() {
    int base = original.isEmpty() ? 0 : (lastKey + 1);
    return ThreadLocalRandom.current().nextInt(base, Integer.MAX_VALUE);
  }

  public long initialSize() {
    return original.size();
  }

  public long maximumSize() {
    assertThat("Invalid usage of context", maximumSize, is(not(nullValue())));
    return maximumSize.max();
  }

  public boolean isUnbounded() {
    return (maximumSize == MaximumSize.DISABLED) || (maximumSize == MaximumSize.UNREACHABLE);
  }

  /** The initial entries in the cache, iterable in insertion order. */
  public Map<Integer, Integer> original() {
    return original;
  }

  public ReferenceType keyReferenceType() {
    return ReferenceType.STRONG;
  }

  public ReferenceType valueReferenceType() {
    return ReferenceType.STRONG;
  }

  public Listener removalListenerType() {
    return removalListenerType;
  }

  @SuppressWarnings("unchecked")
  public <R extends RemovalListener<K, V>, K, V> R removalListener() {
    return (R) requireNonNull(removalListener);
  }

  public boolean isRecordingStats() {
    return (stats == Stats.ENABLED);
  }

  public CacheStats stats() {
    return cache.stats();
  }

  public CacheContext copy() {
    CacheContext context = new CacheContext();

    context.removalListener = removalListenerType.create();
    context.removalListenerType = removalListenerType;
    context.initialCapacity = initialCapacity;
    context.maximumSize = maximumSize;
    context.population = population;
    context.executor = executor;
    context.stats = stats;

    context.keyStrength = keyStrength;
    context.valueStrength = valueStrength;

    context.isLoading = isLoading;
    context.cache = cache;

    context.firstKey = firstKey;
    context.middleKey = middleKey;
    context.lastKey = lastKey;

    return context;
  }

  @Override
  public boolean equals(Object o) {
    return EqualsBuilder.reflectionEquals(this, o);
  }

  @Override
  public int hashCode() {
    return HashCodeBuilder.reflectionHashCode(this);
  }

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this);
  }
}
