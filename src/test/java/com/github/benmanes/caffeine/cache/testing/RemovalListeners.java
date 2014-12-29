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
package com.github.benmanes.caffeine.cache.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.cache.RemovalNotification;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class RemovalListeners {

  private RemovalListeners() {}

  public static <K, V> RemovalListener<K, V> ignoring() {
    return new RemovalListener<K, V>() {
      @Override public void onRemoval(RemovalNotification<K, V> notification) {}
    };
  }

  public static <K, V> RemovalListener<K, V> consuming() {
    return new ConsumingRemovalListener<K, V>();
  }

  public static <K, V> RemovalListener<K, V> rejecting() {
    return new RejectingRemovalListener<K, V>();
  }

  public static final class RejectingRemovalListener<K, V> implements RemovalListener<K, V> {
    public boolean reject = true;

    @Override public void onRemoval(RemovalNotification<K, V> notification) {
      if (reject) {
        throw new RejectedExecutionException("Rejected eviction of " + notification);
      }
    }
  }

  public static final class ConsumingRemovalListener<K, V> implements RemovalListener<K, V> {
    private final List<RemovalNotification<K, V>> evicted;

    public ConsumingRemovalListener() {
      this.evicted = new ArrayList<>();
    }

    @Override
    public synchronized void onRemoval(RemovalNotification<K, V> notification) {
      evicted.add(notification);
    }

    public List<RemovalNotification<K, V>> evicted() {
      return evicted;
    }
  }
}
