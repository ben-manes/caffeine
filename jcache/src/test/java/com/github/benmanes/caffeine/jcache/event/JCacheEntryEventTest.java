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
package com.github.benmanes.caffeine.jcache.event;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import java.util.Map;

import javax.cache.Cache;
import javax.cache.event.EventType;

import org.mockito.Mockito;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheEntryEventTest {
  @SuppressWarnings("unchecked")
  Cache<Integer, Integer> cache = Mockito.mock(Cache.class);
  JCacheEntryEvent<Integer, Integer> event = new JCacheEntryEvent<>(
      cache, EventType.CREATED, 1, true, 2, 3);

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void unwrap_fail() {
    event.unwrap(Map.Entry.class);
  }

  @Test
  public void unwrap() {
    assertThat(event.unwrap(Cache.Entry.class), sameInstance(event));
  }

  @Test
  public void isOldValueAvailable_false() {
    JCacheEntryEvent<Integer, Integer> e =
        new JCacheEntryEvent<>(cache, EventType.CREATED, 1, false, null, 3);
    assertThat(e.isOldValueAvailable(), is(false));
  }

  @Test
  public void isOldValueAvailable() {
    assertThat(event.isOldValueAvailable(), is(true));
  }

  @Test
  public void getOldValue() {
    assertThat(event.getOldValue(), is(2));
  }
}
