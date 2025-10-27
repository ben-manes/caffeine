/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import java.io.Serializable;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;

import org.jctools.queues.atomic.MpscUnboundedAtomicArrayQueue;
import org.jspecify.annotations.Nullable;

/**
 * Some common removal listener implementations for tests.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class RemovalListeners {

  private RemovalListeners() {}

  /** A removal listener that stores the notifications for inspection. */
  public static <K, V> ConsumingRemovalListener<K, V> consuming() {
    return new ConsumingRemovalListener<>();
  }

  /** A removal listener that throws an exception if a notification arrives. */
  public static <K, V> RemovalListener<K, V> rejecting() {
    return new RejectingRemovalListener<>();
  }

  private static void validate(@Nullable Object key, @Nullable Object value, RemovalCause cause) {
    if (cause != RemovalCause.COLLECTED) {
      requireNonNull(key);
      requireNonNull(value);
    }
    requireNonNull(cause);
  }

  public static final class RejectingRemovalListener<K, V>
      implements RemovalListener<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    public int rejected;

    @Override
    public void onRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
      validate(key, value, cause);
      rejected++;
      throw new RejectedExecutionException("Rejected eviction of " +
          new RemovalNotification<>(key, value, cause));
    }
  }

  public static final class ConsumingRemovalListener<K, V>
      implements RemovalListener<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial")
    private final Collection<RemovalNotification<K, V>> removed;

    public ConsumingRemovalListener() {
      this.removed = new MpscUnboundedAtomicArrayQueue<>(8);
    }

    public ConsumingRemovalListener(Collection<RemovalNotification<K, V>> removed) {
      this.removed = requireNonNull(removed);
    }

    @Override
    public void onRemoval(@Nullable K key, @Nullable V value, RemovalCause cause) {
      validate(key, value, cause);
      removed.add(new RemovalNotification<>(key, value, cause));
    }

    public Collection<RemovalNotification<K, V>> removed() {
      return removed;
    }

    private Object writeReplace() {
      return new ConsumingRemovalListener<>(new CopyOnWriteArrayList<>(removed));
    }
  }
}
