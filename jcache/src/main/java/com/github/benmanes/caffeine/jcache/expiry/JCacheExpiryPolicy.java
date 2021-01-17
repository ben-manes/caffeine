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
package com.github.benmanes.caffeine.jcache.expiry;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.Objects;

import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A customized expiration policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class JCacheExpiryPolicy implements ExpiryPolicy, Serializable {
  private static final long serialVersionUID = 1L;

  private final Duration creation;
  private final @Nullable Duration update;
  private final @Nullable Duration access;

  public JCacheExpiryPolicy(Duration creation,
      @Nullable Duration update, @Nullable Duration access) {
    this.creation = requireNonNull(creation);
    this.update = update;
    this.access = access;
  }

  @Override
  public Duration getExpiryForCreation() {
    return creation;
  }

  @Override
  public @Nullable Duration getExpiryForUpdate() {
    return update;
  }

  @Override
  public @Nullable Duration getExpiryForAccess() {
    return access;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    } else if (!(o instanceof ExpiryPolicy)) {
      return false;
    }
    ExpiryPolicy policy = (ExpiryPolicy) o;
    return Objects.equals(creation, policy.getExpiryForCreation())
        && Objects.equals(update, policy.getExpiryForUpdate())
        && Objects.equals(access, policy.getExpiryForAccess());
  }

  @Override
  public int hashCode() {
    return Objects.hash(creation, update, access);
  }
}
