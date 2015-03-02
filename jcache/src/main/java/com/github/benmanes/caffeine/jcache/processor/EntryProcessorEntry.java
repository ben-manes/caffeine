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
package com.github.benmanes.caffeine.jcache.processor;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import javax.cache.Cache;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.MutableEntry;

/**
 * An entry that is consumed by an {@link EntryProcessor}. The updates to the entry are replayed
 * on the cache when the processor completes.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EntryProcessorEntry<K, V> implements MutableEntry<K, V> {
  private K key;
  private V value;

  public EntryProcessorEntry(K key, V value, boolean exists) {
    this.key = key;
    this.value = value;
  }

  @Override
  public boolean exists() {
    return (value != null);
  }

  @Override
  public K getKey() {
    return key;
  }

  @Override
  public V getValue() {
    return value;
  }

  @Override
  public void remove() {
    value = null;
  }

  @Override
  public void setValue(V value) {
    this.value = requireNonNull(value);
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
  public boolean equals(Object o) {
    if (!(o instanceof Cache.Entry<?, ?>)) {
      return false;
    }
    Cache.Entry<?, ?> entry = (Cache.Entry<?, ?>) o;
    return Objects.equals(key, entry.getKey())
        && Objects.equals(value, entry.getValue());
  }

  @Override
  public int hashCode() {
    return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
  }

  @Override
  public String toString() {
    return key + "=" + value;
  }
}
