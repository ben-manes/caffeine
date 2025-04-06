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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Iterator;
import java.util.Map;

import javax.cache.Cache;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.EventType;

import org.mockito.Mockito;
import org.testng.annotations.Test;

import com.google.common.collect.testing.IteratorFeature;
import com.google.common.collect.testing.IteratorTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheEntryEventTest {

  @Test
  public void unwrap_fail() {
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          1, /* hasOldValue= */ true, 2, 3);
      assertThrows(IllegalArgumentException.class, () -> event.unwrap(Map.Entry.class));
    }
  }

  @Test
  public void unwrap() {
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          1, /* hasOldValue= */ true, 2, 3);
      assertThat(event.unwrap(Cache.Entry.class)).isSameInstanceAs(event);
    }
  }

  @Test
  public void isOldValueAvailable_false() {
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      var entry = new JCacheEntryEvent<>(cache, EventType.CREATED,
          1, /* hasOldValue= */ false, null, 3);
      assertThat(entry.isOldValueAvailable()).isFalse();
    }
  }

  @Test
  public void isOldValueAvailable() {
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          1, /* hasOldValue= */ true, 2, 3);
      assertThat(event.isOldValueAvailable()).isTrue();
    }
  }

  @Test
  public void getOldValue() {
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          1, /* hasOldValue= */ true, 2, 3);
      assertThat(event.getOldValue()).isEqualTo(2);
    }
  }

  @Test
  public void iterable() {
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          1, /* hasOldValue= */ true, 2, 3);
      var tester = new IteratorTester<>(
          6, IteratorFeature.UNMODIFIABLE, event, IteratorTester.KnownOrder.KNOWN_ORDER) {
        @Override protected
            Iterator<CacheEntryEvent<? extends Integer, ? extends Integer>> newTargetIterator() {
          return event.iterator();
        }
      };
      tester.test();
      tester.testForEachRemaining();
    }
  }
}
