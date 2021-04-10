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

import java.io.Closeable;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.cache.event.CacheEntryCreatedListener;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryExpiredListener;
import javax.cache.event.CacheEntryListener;
import javax.cache.event.CacheEntryRemovedListener;
import javax.cache.event.CacheEntryUpdatedListener;
import javax.cache.event.EventType;

/**
 * A decorator that dispatches the event iff the listener supports that action.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class EventTypeAwareListener<K, V> implements CacheEntryCreatedListener<K, V>,
    CacheEntryUpdatedListener<K, V>, CacheEntryRemovedListener<K, V>,
    CacheEntryExpiredListener<K, V>, Closeable {
  static final Logger logger = Logger.getLogger(EventTypeAwareListener.class.getName());

  final CacheEntryListener<? super K, ? super V> listener;

  public EventTypeAwareListener(CacheEntryListener<? super K, ? super V> listener) {
    this.listener = requireNonNull(listener);
  }

  /** Returns if the backing listener consumes this type of event. */
  @SuppressWarnings("PMD.SwitchStmtsShouldHaveDefault")
  public boolean isCompatible(EventType eventType) {
    switch (eventType) {
      case CREATED:
        return (listener instanceof CacheEntryCreatedListener<?, ?>);
      case UPDATED:
        return (listener instanceof CacheEntryUpdatedListener<?, ?>);
      case REMOVED:
        return (listener instanceof CacheEntryRemovedListener<?, ?>);
      case EXPIRED:
        return (listener instanceof CacheEntryExpiredListener<?, ?>);
    }
    throw new IllegalStateException("Unknown event type: " + eventType);
  }

  /** Processes the event and logs if an exception is thrown. */
  @SuppressWarnings("PMD.SwitchStmtsShouldHaveDefault")
  public void dispatch(JCacheEntryEvent<K, V> event) {
    try {
      if (event.getSource().isClosed()) {
        return;
      }
      switch (event.getEventType()) {
        case CREATED:
          onCreated(event);
          return;
        case UPDATED:
          onUpdated(event);
          return;
        case REMOVED:
          onRemoved(event);
          return;
        case EXPIRED:
          onExpired(event);
          return;
      }
      throw new IllegalStateException("Unknown event type: " + event.getEventType());
    } catch (Exception e) {
      logger.log(Level.WARNING, null, e);
    } catch (Throwable t) {
      logger.log(Level.SEVERE, null, t);
    }
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
    if (listener instanceof CacheEntryUpdatedListener<?, ?>) {
      ((CacheEntryUpdatedListener<K, V>) listener).onUpdated(events);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onRemoved(Iterable<CacheEntryEvent<? extends K, ? extends V>> events) {
    if (listener instanceof CacheEntryRemovedListener<?, ?>) {
      ((CacheEntryRemovedListener<K, V>) listener).onRemoved(events);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onExpired(Iterable<CacheEntryEvent<? extends K, ? extends V>> events) {
    if (listener instanceof CacheEntryExpiredListener<?, ?>) {
      ((CacheEntryExpiredListener<K, V>) listener).onExpired(events);
    }
  }

  @Override
  public void close() throws IOException {
    if (listener instanceof Closeable) {
      ((Closeable) listener).close();
    }
  }
}
