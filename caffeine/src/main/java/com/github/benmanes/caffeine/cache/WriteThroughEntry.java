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
package com.github.benmanes.caffeine.cache;

import static java.util.Objects.requireNonNull;

import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.ConcurrentMap;

/**
 * An entry that allows updates to write through to the backing map.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class WriteThroughEntry<K, V> extends SimpleEntry<K, V> {
  private static final long serialVersionUID = 1;

  @SuppressWarnings("serial")
  private final ConcurrentMap<K, V> map;

  WriteThroughEntry(ConcurrentMap<K, V> map, K key, V value) {
    super(key, value);
    this.map = requireNonNull(map);
  }

  @Override
  @SuppressWarnings("PMD.LinguisticNaming")
  public V setValue(V value) {
    // See ConcurrentHashMap: "Sets our entry's value and writes through to the map. The value to
    // return is somewhat arbitrary here. Since we do not necessarily track asynchronous changes,
    // the most recent "previous" value could be different from what we return (or could even have
    // been removed, in which case the put will re-establish). We do not and cannot guarantee more."
    map.put(getKey(), value);
    return super.setValue(value);
  }

  Object writeReplace() {
    return new SimpleEntry<>(this);
  }
}
