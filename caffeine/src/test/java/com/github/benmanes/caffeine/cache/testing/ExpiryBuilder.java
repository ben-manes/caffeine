/*
 * Copyright 2017 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.testing;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;

import com.github.benmanes.caffeine.cache.Expiry;

/**
 * A builder for unit test convenience.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ExpiryBuilder {
  static final int UNSET = -1;

  private long createNanos = UNSET;
  private long updateNanos = UNSET;
  private long readNanos = UNSET;

  private ExpiryBuilder(long createNanos) {
    this.createNanos = createNanos;
  }

  /** Sets the fixed creation expiration time. */
  public static ExpiryBuilder expiringAfterCreate(long nanos) {
    return new ExpiryBuilder(nanos);
  }

  /** Sets the fixed update expiration time. */
  public ExpiryBuilder expiringAfterUpdate(long nanos) {
    updateNanos = nanos;
    return this;
  }

  /** Sets the fixed read expiration time. */
  public ExpiryBuilder expiringAfterRead(long nanos) {
    readNanos = nanos;
    return this;
  }

  public <K, V> Expiry<K, V> build() {
    return new FixedExpiry<K, V>(createNanos, updateNanos, readNanos);
  }

  private static final class FixedExpiry<K, V> implements Expiry<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    private final long createNanos;
    private final long updateNanos;
    private final long readNanos;

    FixedExpiry(long createNanos, long updateNanos, long readNanos) {
      this.createNanos = createNanos;
      this.updateNanos = updateNanos;
      this.readNanos = readNanos;
    }

    @Override
    public long expireAfterCreate(K key, V value, long currentTime) {
      requireNonNull(key);
      requireNonNull(value);
      return createNanos;
    }
    @Override
    public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
      requireNonNull(key);
      requireNonNull(value);
      return (updateNanos == UNSET) ? currentDuration : updateNanos;
    }
    @Override
    public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
      requireNonNull(key);
      requireNonNull(value);
      return (readNanos == UNSET) ? currentDuration : readNanos;
    }
  }
}
