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

import java.util.Objects;

import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryEventFilter;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryListenerException;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;

/**
 * A filter that determines if the listener can process the event type before delegating to the
 * decorated filter.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class EventTypeFilter<K, V> implements CacheEntryEventFilter<K, V> {
  private final CacheEntryEventFilter<? super K, ? super V> filter;
  private final CacheEntryListener<? super K, ? super V> listener;

  public EventTypeFilter(CacheEntryListener<? super K, ? super V> listener,
      CacheEntryEventFilter<? super K, ? super V> filter) {
    this.listener = requireNonNull(listener);
    this.filter = requireNonNull(filter);
  }

  @Override
  public boolean evaluate(CacheEntryEvent<? extends K, ? extends V> event) {
    return isCompatible(event) && filter.evaluate(event);
  }

  @SuppressWarnings("PMD.SwitchStmtsShouldHaveDefault")
  private boolean isCompatible(CacheEntryEvent<? extends K, ? extends V> event) {
    switch (event.getEventType()) {
      case CREATED:
        return (listener instanceof CacheEntryCreatedListener<?, ?>);
      case UPDATED:
        return (listener instanceof CacheEntryUpdatedListener<?, ?>);
      case REMOVED:
        return (listener instanceof CacheEntryRemovedListener<?, ?>);
      case EXPIRED:
        return (listener instanceof CacheEntryExpiredListener<?, ?>);
    }
    throw new CacheEntryListenerException("Unknown event type: " + event.getEventType());
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof EventTypeFilter<?, ?>)) {
      return false;
    }
    EventTypeFilter<?, ?> other = (EventTypeFilter<?, ?>) o;
    return Objects.equals(listener, other.listener)
        && Objects.equals(filter, other.filter);
  }

  @Override
  public int hashCode() {
    return filter.hashCode();
  }
}
