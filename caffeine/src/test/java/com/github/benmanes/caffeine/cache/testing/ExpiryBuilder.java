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
import java.time.Duration;

import com.github.benmanes.caffeine.cache.Expiry;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * A builder for unit test convenience.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class ExpiryBuilder {
  private final Duration create;
  private Duration update;
  private Duration read;

  private ExpiryBuilder(Duration create) {
    this.create = create;
  }

  /** Sets the fixed creation expiration time. */
  public static ExpiryBuilder expiringAfterCreate(Duration duration) {
    return new ExpiryBuilder(duration);
  }

  /** Sets the fixed update expiration time. */
  @CanIgnoreReturnValue
  public ExpiryBuilder expiringAfterUpdate(Duration duration) {
    update = duration;
    return this;
  }

  /** Sets the fixed read expiration time. */
  @CanIgnoreReturnValue
  public ExpiryBuilder expiringAfterRead(Duration duration) {
    read = duration;
    return this;
  }

  public <K, V> Expiry<K, V> build() {
    return new FixedExpiry<K, V>(create, update, read);
  }

  private static final class FixedExpiry<K, V> implements Expiry<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    private final Duration create;
    private final Duration update;
    private final Duration read;

    FixedExpiry(Duration create, Duration update, Duration read) {
      this.create = create;
      this.update = update;
      this.read = read;
    }

    @Override
    public long expireAfterCreate(K key, V value, long currentTime) {
      requireNonNull(key);
      requireNonNull(value);
      return create.toNanos();
    }
    @Override
    public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
      requireNonNull(key);
      requireNonNull(value);
      return (update == null) ? currentDuration : update.toNanos();
    }
    @Override
    public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
      requireNonNull(key);
      requireNonNull(value);
      return (read == null) ? currentDuration : read.toNanos();
    }
  }
}
