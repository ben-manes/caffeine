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
package com.github.benmanes.caffeine.eclipse;

import java.util.concurrent.ConcurrentMap;

import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function0;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.procedure.Procedure;
import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.eclipse.collections.impl.map.mutable.MapAdapter;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ConcurrentMapAdapter<K, V> extends MapAdapter<K, V>
    implements ConcurrentMutableMap<K, V> {
  private static final long serialVersionUID = 1L;

  public ConcurrentMapAdapter(ConcurrentMap<K, V> delegate) {
    super(delegate);
  }
  @Override public V putIfAbsent(K key, V value) {
    return delegate.putIfAbsent(key, value);
  }
  @Override public boolean remove(Object key, Object value) {
    return delegate.remove(key, value);
  }
  @Override public V replace(K key, V value) {
    return delegate.replace(key, value);
  }
  @Override public boolean replace(K key, V oldValue, V newValue) {
    return delegate.replace(key, oldValue, newValue);
  }
  @SuppressWarnings("FunctionalInterfaceClash")
  @Override public ConcurrentMutableMap<K, V> tap(Procedure<? super V> procedure) {
    each(procedure);
    return this;
  }
  @Override public V getIfAbsentPut(K key, Function0<? extends V> function) {
    return delegate.computeIfAbsent(key, k -> function.get());
  }
  @Override public V updateValue(K key, Function0<? extends V> factory,
      Function<? super V, ? extends V> function) {
    return delegate.compute(key, (k, oldValue) -> {
      return function.valueOf((oldValue == null) ? factory.get() : oldValue);
    });
  }
  @Override public <P> V updateValueWith(K key, Function0<? extends V> factory,
      Function2<? super V, ? super P, ? extends V> function, P parameter) {
    return delegate.compute(key, (k, oldValue) -> {
      return function.value((oldValue == null) ? factory.get() : oldValue, parameter);
    });
  }
}
