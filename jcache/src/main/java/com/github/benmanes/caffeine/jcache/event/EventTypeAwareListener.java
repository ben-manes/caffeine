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

import static java.util.Objects.requireNonNull;

import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;

/**
 * A decorator that dispatches the event iff the listener supports that action.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class EventTypeAwareListener<K, V> implements CacheEntryCreatedListener<K, V>,
    CacheEntryUpdatedListener<K, V>, CacheEntryRemovedListener<K, V>,
    CacheEntryExpiredListener<K, V> {
  private final CacheEntryListener<? super K, ? super V> listener;

  public EventTypeAwareListener(CacheEntryListener<? super K, ? super V> listener) {
    this.listener = requireNonNull(listener);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onCreated(Iterable<CacheEntryEvent<? extends K, ? extends V>> events) {
    if (listener instanceof CacheEntryCreatedListener<?, ?>) {
      ((CacheEntryCreatedListener<K, V>) listener).onCreated(events);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onUpdated(Iterable<CacheEntryEvent<? extends K, ? extends V>> events) {
    if (listener instanceof CacheEntryCreatedListener<?, ?>) {
      ((CacheEntryUpdatedListener<K, V>) listener).onUpdated(events);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onRemoved(Iterable<CacheEntryEvent<? extends K, ? extends V>> events) {
    if (listener instanceof CacheEntryCreatedListener<?, ?>) {
      ((CacheEntryRemovedListener<K, V>) listener).onRemoved(events);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onExpired(Iterable<CacheEntryEvent<? extends K, ? extends V>> events) {
    if (listener instanceof CacheEntryCreatedListener<?, ?>) {
      ((CacheEntryExpiredListener<K, V>) listener).onExpired(events);
    }
  }
}
