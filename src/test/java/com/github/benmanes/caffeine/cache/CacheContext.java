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
package com.github.benmanes.caffeine.cache;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nullable;

/**
 * The cache configuration context for a test case.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheContext {
  @Nullable Integer firstKey;
  @Nullable Integer midKey;
  @Nullable Integer lastKey;
  @Nullable Integer maximumSize;

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
    int base = (lastKey == null) ? 0 : (lastKey + 1);
    return ThreadLocalRandom.current().nextInt(base, Integer.MAX_VALUE);
  }

  public int getMaximumSize() {
    assertThat("Invalid usage of context", maximumSize, is(not(nullValue())));
    return maximumSize;
  }

  public boolean isUnbounded() {
    return (maximumSize != null);
  }

  public CacheContext copy() {
    CacheContext context = new CacheContext();
    context.maximumSize = maximumSize;
    context.firstKey = firstKey;
    context.midKey = midKey;
    context.lastKey = lastKey;
    return context;
  }
}
