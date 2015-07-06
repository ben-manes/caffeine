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
package com.github.benmanes.caffeine.cache.testing;

import java.io.Serializable;

import com.github.benmanes.caffeine.cache.CacheWriter;
import com.github.benmanes.caffeine.cache.RemovalCause;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class RejectingCacheWriter<K, V> implements CacheWriter<K, V>, Serializable {
  private static final long serialVersionUID = 1L;

  private boolean reject = true;

  public void ignoreWrites() {
    reject = false;
  }

  public void rejectWrites() {
    reject = true;
  }

  @Override
  public void write(K key, V value) {
    if (reject) {
      throw new WriteException();
    }
  }

  @Override
  public void delete(K key, V value, RemovalCause cause) {
    if (reject) {
      throw new DeleteException();
    }
  }

  @Override
  public boolean equals(Object o) {
    return (o instanceof RejectingCacheWriter);
  }

  @Override
  public int hashCode() {
    return RejectingCacheWriter.class.hashCode();
  }

  public static final class WriteException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  public static final class DeleteException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
