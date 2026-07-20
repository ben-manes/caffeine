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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import javax.cache.Cache;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryListenerException;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.event.EventType;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class EventTypeAwareListenerTest {

  @Test @Tag("isolated")
  @SuppressWarnings({"CheckReturnValue", "EnumOrdinal", "try"})
  void isCompatible_unknownEventType() throws Exception {
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

  @Test @Tag("isolated")
  @SuppressWarnings({"CheckReturnValue", "EnumOrdinal", "try"})
  void dispatch_unknownEventType() throws Exception {
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
  @SuppressWarnings("try")
  void suppress() throws Exception {
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
  @SuppressWarnings("try")
  void closed() throws Exception {
    CacheEntryListener<Integer, Integer> listener = Mockito.mock();
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      when(cache.isClosed()).thenReturn(true);
      var thrown = forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2));
      assertThat(thrown).isNull();
      verifyNoInteractions(listener);
    }
  }

  @SuppressWarnings("try")
  @ParameterizedTest @MethodSource("exceptions")
  void created_failure(Throwable error) throws Exception {
    CacheEntryCreatedListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      var thrown = forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2));
      assertReturned(error, thrown);
    }
    verify(listener).onCreated(anyIterable());
  }

  @SuppressWarnings("try")
  @ParameterizedTest @MethodSource("exceptions")
  void updated_failure(Throwable error) throws Exception {
    CacheEntryUpdatedListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      var thrown = forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.UPDATED,
          /* key= */ 1, /* hasOldValue= */ true, /* oldValue= */ 2, /* newValue= */ 3));
      assertReturned(error, thrown);
    }
    verify(listener).onUpdated(anyIterable());
  }

  @SuppressWarnings("try")
  @ParameterizedTest @MethodSource("exceptions")
  void removed_failure(Throwable error) throws Exception {
    CacheEntryRemovedListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      var thrown = forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.REMOVED,
          /* key= */ 1, /* hasOldValue= */ true, /* oldValue= */ 2, /* newValue= */ null));
      assertReturned(error, thrown);
    }
    verify(listener).onRemoved(anyIterable());
  }

  @SuppressWarnings("try")
  @ParameterizedTest @MethodSource("exceptions")
  void expired_failure(Throwable error) throws Exception {
    CacheEntryExpiredListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      var thrown = forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.EXPIRED,
          /* key= */ 1, /* hasOldValue= */ true, /* oldValue= */ 2, /* newValue= */ null));
      assertReturned(error, thrown);
    }
    verify(listener).onExpired(anyIterable());
  }

  @Test
  @SuppressWarnings("try")
  void dispatch_error_rethrown() throws Exception {
    var error = new AssertionError("listener");
    CacheEntryCreatedListener<Integer, Integer> listener = Mockito.mock(answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener);
        Cache<Integer, Integer> cache = Mockito.mock()) {
      // an Error is not a listener contract violation to wrap; it is logged and rethrown as-is
      var e = assertThrows(AssertionError.class, () ->
          forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.CREATED,
              /* key= */ 1, /* hasOldValue= */ false, /* oldValue= */ null, /* newValue= */ 2)));
      assertThat(e).isSameInstanceAs(error);
    }
    verify(listener).onCreated(anyIterable());
  }

  /** A {@link CacheEntryListenerException} is returned as-is; anything else is wrapped in one. */
  private static void assertReturned(
      Throwable error, @Nullable CacheEntryListenerException thrown) {
    if (error instanceof CacheEntryListenerException) {
      assertThat(thrown).isSameInstanceAs(error);
    } else {
      assertThat(thrown).hasCauseThat().isSameInstanceAs(error);
    }
  }

  static Stream<Throwable> exceptions() {
    return Stream.of(new CacheEntryListenerException(),
        new RuntimeException(), new Exception(), new Throwable());
  }
}
