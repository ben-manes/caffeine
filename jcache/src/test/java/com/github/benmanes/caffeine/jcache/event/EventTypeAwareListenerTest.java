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

import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;

import javax.cache.Cache;
import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.event.EventType;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("unchecked")
public final class EventTypeAwareListenerTest {
  @Mock Cache<Integer, Integer> cache;

  @BeforeMethod
  public void before() throws Exception {
    MockitoAnnotations.openMocks(this).close();
  }

  @Test
  public void closed() throws IOException {
    when(cache.isClosed()).thenReturn(true);
    CacheEntryListener<Integer, Integer> listener = Mockito.mock();
    try (var forwarder = new EventTypeAwareListener<>(listener)) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key */ 1, /* hasOldValue */ false, /* oldValue */ null, /* newValue */ 2));
      verifyNoInteractions(listener);
    }
  }

  @Test(dataProvider = "exceptions")
  public void created_failure(Throwable error) throws IOException {
    var listener = Mockito.mock(CacheEntryCreatedListener.class, answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener)) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.CREATED,
          /* key */ 1, /* hasOldValue */ false, /* oldValue */ null, /* newValue */ 2));
    }
    verify(listener).onCreated(anyIterable());
  }

  @Test(dataProvider = "exceptions")
  public void updated_failure(Throwable error) throws IOException {
    var listener = Mockito.mock(CacheEntryUpdatedListener.class, answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener)) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.UPDATED,
          /* key */ 1, /* hasOldValue */ true, /* oldValue */ 2, /* newValue */ 3));
    }
    verify(listener).onUpdated(anyIterable());
  }

  @Test(dataProvider = "exceptions")
  public void removed_failure(Throwable error) throws IOException {
    var listener = Mockito.mock(CacheEntryRemovedListener.class, answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener)) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.REMOVED,
          /* key */ 1, /* hasOldValue */ true, /* oldValue */ 2, /* newValue */ null));
    }
    verify(listener).onRemoved(anyIterable());
  }

  @Test(dataProvider = "exceptions")
  public void expired_failure(Throwable error) throws IOException {
    var listener = Mockito.mock(CacheEntryExpiredListener.class, answer -> { throw error; });
    try (var forwarder = new EventTypeAwareListener<>(listener)) {
      forwarder.dispatch(new JCacheEntryEvent<>(cache, EventType.EXPIRED,
          /* key */ 1, /* hasOldValue */ true, /* oldValue */ 2, /* newValue */ null));
    }
    verify(listener).onExpired(anyIterable());
  }

  @DataProvider(name = "exceptions")
  public Object[] providesExceptions() {
    return new Object[] { new Exception(), new Throwable() };
  }
}
