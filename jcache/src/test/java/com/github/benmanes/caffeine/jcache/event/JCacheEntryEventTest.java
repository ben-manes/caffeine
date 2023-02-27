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
import static org.junit.Assert.assertThrows;

import java.util.Iterator;
import java.util.Map;

import javax.cache.Cache;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.EventType;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.google.common.collect.testing.IteratorFeature;
import com.google.common.collect.testing.IteratorTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheEntryEventTest {
  @Mock Cache<Integer, Integer> cache;

  JCacheEntryEvent<Integer, Integer> event;

  @BeforeTest
  public void before() throws Exception {
    MockitoAnnotations.openMocks(this).close();
    event = new JCacheEntryEvent<>(cache, EventType.CREATED, 1, true, 2, 3);
  }

  @Test
  public void unwrap_fail() {
    assertThrows(IllegalArgumentException.class, () -> event.unwrap(Map.Entry.class));
  }

  @Test
  public void unwrap() {
    assertThat(event.unwrap(Cache.Entry.class)).isSameInstanceAs(event);
  }

  @Test
  public void isOldValueAvailable_false() {
    var entry = new JCacheEntryEvent<>(cache, EventType.CREATED, 1, false, null, 3);
    assertThat(entry.isOldValueAvailable()).isFalse();
  }

  @Test
  public void isOldValueAvailable() {
    assertThat(event.isOldValueAvailable()).isTrue();
  }

  @Test
  public void getOldValue() {
    assertThat(event.getOldValue()).isEqualTo(2);
  }

  @Test
  public void iterable() {
    var tester = new IteratorTester<CacheEntryEvent<? extends Integer, ? extends Integer>>(
        6, IteratorFeature.UNMODIFIABLE, event, IteratorTester.KnownOrder.KNOWN_ORDER) {
      @Override
      protected Iterator<CacheEntryEvent<? extends Integer, ? extends Integer>> newTargetIterator() {
        return event.iterator();
      }
    };
    tester.test();
    tester.testForEachRemaining();
  }
}
