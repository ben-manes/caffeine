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
package com.github.benmanes.caffeine.jcache;

import static java.util.Objects.requireNonNull;

/**
 * A value with an expiration timestamp.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Expirable<V> {
  private final V value;

  private volatile long expireTimeMS;

  public Expirable(V value, long expireTimeMS) {
    this.value = requireNonNull(value);
    this.expireTimeMS = expireTimeMS;
  }

  public V get() {
    return value;
  }

  public void setExpireTimeMS(long expireTimeMS) {
    this.expireTimeMS = expireTimeMS;
  }

  public boolean hasExpired(long currentTimeMS) {
    return (expireTimeMS <= currentTimeMS);
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof Expirable<?>)) {
      return false;
    }
    Expirable<?> expirable = (Expirable<?>) o;
    return value.equals(expirable.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
