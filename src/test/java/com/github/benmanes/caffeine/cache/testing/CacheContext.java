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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.google.common.base.MoreObjects;

/**
 * The cache configuration context for a test case.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheContext {
  @Nullable RemovalListener<Integer, Integer> removalListener;
  Listener removalListenerType;
  Population population;

  Integer initialCapacity;
  Executor executor;

  @Nullable Integer maximumSize;
  @Nullable Integer firstKey;
  @Nullable Integer midKey;
  @Nullable Integer lastKey;

  public int getFirstKey() {
    assertThat("Invalid usage of context", firstKey, is(not(nullValue())));
    return firstKey;
  }

  public int getMiddleKey() {
    assertThat("Invalid usage of context", midKey, is(not(nullValue())));
    return midKey;
  }

  public int getLastKey() {
    assertThat("Invalid usage of context", lastKey, is(not(nullValue())));
    return lastKey;
  }

  public int getAbsentKey() {
    int base = initiallyEmpty() ? 0 : (lastKey + 1);
    return ThreadLocalRandom.current().nextInt(base, Integer.MAX_VALUE);
  }

  public boolean initiallyEmpty() {
    return (lastKey == null);
  }

  public long getInitialSize() {
    return initiallyEmpty() ? 0 : (1 + lastKey - firstKey);
  }

  public long getMaximumSize() {
    assertThat("Invalid usage of context", maximumSize, is(not(nullValue())));
    return maximumSize;
  }

  public boolean isUnbounded() {
    return (maximumSize == null);
  }

  public Listener getRemovalListenerType() {
    return removalListenerType;
  }

  @SuppressWarnings("unchecked")
  public <R extends RemovalListener<K, V>, K, V> R getRemovalListener() {
    return (R) removalListener;
  }

  public CacheContext copy() {
    CacheContext context = new CacheContext();
    context.removalListenerType = removalListenerType;
    context.removalListener = (removalListenerType == null) ? null : removalListenerType.create();
    context.maximumSize = maximumSize;
    context.firstKey = firstKey;
    context.midKey = midKey;
    context.lastKey = lastKey;
    return context;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("maximum size", isUnbounded() ? "UNBOUNDED" : String.format("%,d", maximumSize))
        .add("removal listener", removalListenerType)
        .add("population", population)
        .toString();
  }
}
