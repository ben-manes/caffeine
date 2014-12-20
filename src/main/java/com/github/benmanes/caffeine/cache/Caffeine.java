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

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Caffeine<K, V> {
  RemovalListener<? super K, ? super V> removalListener;
  int initialCapacity;
  Executor executor;

  public Caffeine() {
    executor = ForkJoinPool.commonPool();
  }

  /** Ensures that the argument expression is true. */
  static void checkArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /** Ensures that the state expression is true. */
  static void checkState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  /** Constructs a new {@code Caffeine} instance with the settings specified in this instance. */
  Caffeine<K, V> copy() {
    @SuppressWarnings("unchecked")
    Caffeine<K, V> copy = (Caffeine<K, V>) Caffeine.newBuilder();
    copy.removalListener = removalListener;
    copy.initialCapacity = initialCapacity;
    copy.executor = executor;
    return copy;
  }

  @SuppressWarnings("unchecked")
  <K1 extends K, V1 extends V> RemovalListener<K1, V1> getRemovalListener() {
    return (RemovalListener<K1, V1>)
        ((removalListener == null) ? NullRemovalListener.INSTANCE : removalListener);
  }

  public static Caffeine<Object, Object> newBuilder() {
    return new Caffeine<Object, Object>();
  }

  public void initialCapacity(int initialCapacity) {
    // TODO(ben): Validate
    this.initialCapacity = initialCapacity;
  }

  public Caffeine<K, V> executor(Executor executor) {
    this.executor = requireNonNull(executor);
    return this;
  }

  public <K1 extends K, V1 extends V> Caffeine<K1, V1> removalListener(
      RemovalListener<? super K1, ? super V1> listener) {
    // TODO(ben): Validate
    this.removalListener = requireNonNull(removalListener);

    @SuppressWarnings("unchecked")
    Caffeine<K1, V1> self = (Caffeine<K1, V1>) this;
    return self;
  }

  public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
    LocalCache<K1, V1> localCache = new UnboundedLocalCache<>(this);
    return new LocalManualCache<K1, V1>(localCache);
  }

  enum NullRemovalListener implements RemovalListener<Object, Object> {
    INSTANCE;

    @Override
    public void onRemoval(RemovalNotification<Object, Object> notification) {}
  }
}
