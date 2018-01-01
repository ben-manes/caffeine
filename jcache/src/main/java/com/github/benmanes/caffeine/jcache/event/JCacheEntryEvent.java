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

import java.util.Iterator;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.event.CacheEntryEvent;
import javax.cache.event.EventType;

/**
 * A cache event dispatched to a listener.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class JCacheEntryEvent<K, V> extends CacheEntryEvent<K, V>
    implements Iterable<CacheEntryEvent<? extends K, ? extends V>> {
  private static final long serialVersionUID = 1L;

  private final K key;
  private final @Nullable V oldValue;
  private final @Nullable V newValue;

  JCacheEntryEvent(Cache<K, V> source, EventType eventType,
      K key, @Nullable V oldValue, @Nullable V newValue) {
    super(source, eventType);
    this.key = requireNonNull(key);
    this.oldValue = oldValue;
    this.newValue = newValue;
  }

  @Override
  public K getKey() {
    return key;
  }

  @Override
  public @Nullable V getValue() {
    return newValue;
  }

  @Override
  public @Nullable V getOldValue() {
    return oldValue;
  }

  @Override
  public boolean isOldValueAvailable() {
    return (oldValue != null);
  }

  @Override
  public <T> T unwrap(Class<T> clazz) {
    if (!clazz.isInstance(this)) {
      throw new IllegalArgumentException("Class " + clazz + " is unknown to this implementation");
    }
    @SuppressWarnings("unchecked")
    T castedEntry = (T) this;
    return castedEntry;
  }

  @Override
  public Iterator<CacheEntryEvent<? extends K, ? extends V>> iterator() {
    return new Iterator<CacheEntryEvent<? extends K, ? extends V>>() {
      boolean hasNext = true;

      @Override
      public boolean hasNext() {
        return hasNext;
      }

      @Override
      public CacheEntryEvent<K, V> next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        hasNext = false;
        return JCacheEntryEvent.this;
      }
    };
  }
}
