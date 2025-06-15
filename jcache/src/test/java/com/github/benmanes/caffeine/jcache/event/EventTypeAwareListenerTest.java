/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import javax.cache.Cache;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.event.EventType;

import org.mockito.Mockito;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EventTypeAwareListenerTest {

  @Test(groups = "isolated")
  @SuppressWarnings({"CheckReturnValue", "EnumOrdinal"})
  public void isCompatible_unknownEventType() throws IOException {
    CacheEntryListener<Integer, Integer> listener = Mockito.mock();
    try (var forwarder = new EventTypeAwareListener<>(listener);
         var eventTypes = Mockito.mockStatic(EventType.class)) {
      var unknown = Mockito.mock(EventType.class);
      when(unknown.ordinal()).thenReturn(4);
      eventTypes.when(EventType::values).thenReturn(new EventType[] {
          EventType.CREATED, EventType.UPDATED, EventType.REMOVED, EventType.EXPIRED, unknown });
      assertThrows(IllegalStateException.class, () -> forwarder.isCompatible(unknown));
      verifyNoInteractions(listener);
    }
  }

  @Test(groups = "isolated")
  @SuppressWarnings({"CheckReturnValue", "EnumOrdinal"})
  public void dispatch_unknownEventType() throws IOException {
    CacheEntryListener<Integer, Integer> listener = Mockito.mock();
    try (var forwarder = new EventTypeAwareListener<>(listener);
         var eventTypes = Mockito.mockStatic(EventType.class);
         Cache<Integer, Integer> cache = Mockito.mock()) {
      var unknown = Mockito.mock(EventType.class);
      when(unknown.ordinal()).thenReturn(4);
      eventTypes.when(EventType::values).thenReturn(new EventType[] {
          EventType.CREATED, EventType.UPDATED, EventType.REMOVED, EventType.EXPIRED, unknown });
      forwarder.dispatch(new JCacheEntryEvent<>(cache, unknown,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2));
      verifyNoInteractions(listener);
    }
  }

  @Test
  public void suppress() throws IOException {
    CacheEntryListener<Integer, Integer> delegate = Mockito.mock();
    try (var listener = new EventTypeAwareListener<>(delegate)) {
      listener.onCreated(List.of());
      listener.onUpdated(List.of());
      listener.onRemoved(List.of());
      listener.onExpired(List.of());
    }
    verifyNoInteractions(delegate);
  }

  @Test
  public void closed() throws IOException {
    CacheEntryListener<Integer, Integer> listener = Mockito.mock();
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      when(cache.isClosed()).thenReturn(true);
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2));
      verifyNoInteractions(listener);
    }
  }

  @Test(dataProvider = "exceptions")
  public void created_failure(Throwable error) throws IOException {
    CacheEntryCreatedListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2));
    }
    verify(listener).onCreated(anyIterable());
  }

  @Test(dataProvider = "exceptions")
  public void updated_failure(Throwable error) throws IOException {
    CacheEntryUpdatedListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.UPDATED,
          /* key= */ 1, /* hasOldValue= */ true, /* oldValue= */ 2, /* newValue= */ 3));
    }
    verify(listener).onUpdated(anyIterable());
  }

  @Test(dataProvider = "exceptions")
  public void removed_failure(Throwable error) throws IOException {
    CacheEntryRemovedListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.REMOVED,
          /* key= */ 1, /* hasOldValue= */ true, /* oldValue= */ 2, /* newValue= */ null));
    }
    verify(listener).onRemoved(anyIterable());
  }

  @Test(dataProvider = "exceptions")
  public void expired_failure(Throwable error) throws IOException {
    CacheEntryExpiredListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.EXPIRED,
          /* key= */ 1, /* hasOldValue= */ true, /* oldValue= */ 2, /* newValue= */ null));
    }
    verify(listener).onExpired(anyIterable());
  }

  @DataProvider(name = "exceptions")
  public Object[] providesExceptions() {
    return new Object[] { new Exception(), new Throwable() };
  }
}
