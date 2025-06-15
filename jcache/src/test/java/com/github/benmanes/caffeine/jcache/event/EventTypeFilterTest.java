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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import javax.cache.Cache;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryListenerException;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.event.EventType;

import org.mockito.Mockito;
import org.testng.annotations.Test;

import com.google.common.testing.EqualsTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EventTypeFilterTest {

  @Test
  public void evaluate_compatible() {
    CacheEntryEventFilter<Integer, Integer> underlying = Mockito.mock();
    CacheEntryCreatedListener<Integer, Integer> listener = Mockito.mock();
    var filter = new EventTypeFilter<>(listener, underlying);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      when(underlying.evaluate(any()))
          .thenReturn(false)
          .thenReturn(true);
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2);
      assertThat(filter.evaluate(event)).isFalse();
      assertThat(filter.evaluate(event)).isTrue();
      verifyNoInteractions(listener);
    }
  }

  @Test
  public void evaluate_incompatible() {
    CacheEntryEventFilter<Integer, Integer> underlying = Mockito.mock();
    CacheEntryListener<Integer, Integer> listener = Mockito.mock();
    var filter = new EventTypeFilter<>(listener, underlying);
    try (Cache<Integer, Integer> cache = Mockito.mock()) {
      var event = new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2);
      assertThat(filter.evaluate(event)).isFalse();
      verifyNoInteractions(cache, listener, underlying);
    }
  }

  @Test(groups = "isolated")
  @SuppressWarnings({"CheckReturnValue", "EnumOrdinal"})
  public void evaluate_unknownEventType() {
    CacheEntryEventFilter<Integer, Integer> underlying = Mockito.mock();
    CacheEntryListener<Integer, Integer> listener = Mockito.mock();
    var filter = new EventTypeFilter<>(listener, underlying);
    try (var eventTypes = Mockito.mockStatic(EventType.class);
         Cache<Integer, Integer> cache = Mockito.mock()) {
      var unknown = Mockito.mock(EventType.class);
      when(unknown.ordinal()).thenReturn(4);
      eventTypes.when(EventType::values).thenReturn(new EventType[] {
          EventType.CREATED, EventType.UPDATED, EventType.REMOVED, EventType.EXPIRED, unknown });
      var event = new JCacheEntryEvent<>(cache, unknown,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2);
      assertThrows(CacheEntryListenerException.class, () -> filter.evaluate(event));
      verifyNoInteractions(cache, listener, underlying);
    }
  }

  @Test
  public void equals() {
    CacheEntryCreatedListener<Integer, Integer> create = events -> {};
    CacheEntryUpdatedListener<Integer, Integer> update = events -> {};
    CacheEntryEventFilter<Integer, Integer> none = event -> false;
    CacheEntryEventFilter<Integer, Integer> all = event -> true;
    new EqualsTester()
        .addEqualityGroup(new EventTypeFilter<>(create, none), new EventTypeFilter<>(create, none))
        .addEqualityGroup(new EventTypeFilter<>(create, all), new EventTypeFilter<>(create, all))
        .addEqualityGroup(new EventTypeFilter<>(update, all), new EventTypeFilter<>(update, all))
        .testEquals();
  }
}
