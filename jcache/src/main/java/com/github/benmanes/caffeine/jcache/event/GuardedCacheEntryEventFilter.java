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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;

import javax.cache.event.CacheEntryEvent;
import javax.cache.event.CacheEntryEventFilter;

import org.jspecify.annotations.Nullable;

/**
 * A {@link CacheEntryEventFilter} implementation that suppresses and logs any exception thrown by
 * the delegate <code>filter</code>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class GuardedCacheEntryEventFilter<K, V> implements CacheEntryEventFilter<K, V> {
  static final Logger logger = System.getLogger(GuardedCacheEntryEventFilter.class.getName());

  private final CacheEntryEventFilter<? super K, ? super V> delegate;

  public GuardedCacheEntryEventFilter(CacheEntryEventFilter<? super K, ? super V> delegate) {
    this.delegate = requireNonNull(delegate);
  }

  @Override
  public boolean evaluate(CacheEntryEvent<? extends K, ? extends V> event) {
    try {
      return delegate.evaluate(event);
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "", e);
      return false;
    } catch (Throwable t) {
      logger.log(Level.ERROR, "", t);
      return false;
    }
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof GuardedCacheEntryEventFilter<?, ?>)) {
      return false;
    }
    var other = (GuardedCacheEntryEventFilter<?, ?>) o;
    return Objects.equals(delegate, other.delegate);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }
}
