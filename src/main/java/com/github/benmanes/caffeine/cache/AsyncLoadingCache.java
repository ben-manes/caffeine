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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A semi-persistent mapping from keys to values. Values are automatically loaded by the cache,
 * and are stored in the cache until either evicted or manually invalidated.
 * <p>
 * Implementations of this interface are expected to be thread-safe, and can be safely accessed
 * by multiple concurrent threads.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
@ThreadSafe
public interface AsyncLoadingCache<K, V> {

  CompletableFuture<V> get(@Nonnull K key);

  @Nonnull
  CompletableFuture<V> get(@Nonnull K key,
      @Nonnull Function<? super K, CompletableFuture<? extends V>> mappingFunction);


  @Nonnull
  Map<K, V> getAll(@Nonnull Iterable<? extends K> keys);

  /**
   * Associates {@code value} with {@code key} in this cache. If the cache previously contained a
   * value associated with {@code key}, the old value is replaced by {@code value}.
   * <p>
   * Prefer {@link #get(Object, Function)} when using the conventional "if cached, return; otherwise
   * create, cache and return" pattern.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @throws NullPointerException if the specified key or value is null
   */
  void put(@Nonnull K key, @Nonnull CompletableFuture<V> value);

  /**
   * Returns a view of the entries stored in this cache as a synchronous {@link LoadingCache}.
   * Modifications made to the synchronous cache directly affect the asynchronous cache.
   *
   * @return a thread-safe synchronous view of this cache
   */
  @Nonnull
  LoadingCache<K, V> synchronous();
}
