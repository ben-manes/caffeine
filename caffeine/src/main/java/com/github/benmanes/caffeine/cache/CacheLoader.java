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

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Computes or retrieves values, based on a key, for use in populating a {@link LoadingCache} or
 * {@link AsyncLoadingCache}.
 * <p>
 * Most implementations will only need to implement {@link #load}. Other methods may be
 * overridden as desired.
 * <p>
 * Usage example:
 * <pre>{@code
 *   CacheLoader<Key, Graph> loader = key -> createExpensiveGraph(key);
 *   LoadingCache<Key, Graph> cache = Caffeine.newBuilder().build(loader);
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@FunctionalInterface
public interface CacheLoader<K, V> {

  /**
   * Computes or retrieves the value corresponding to {@code key}.
   *
   * @param key the non-null key whose value should be loaded
   * @return the value associated with {@code key} or {@code null} if not found
   * @throws RuntimeException or Error, in which case the mapping is unchanged
   */
  @CheckForNull
  V load(@Nonnull K key);

  /**
   * Computes or retrieves the values corresponding to {@code keys}. This method is called by
   * {@link LoadingCache#getAll}.
   * <p>
   * If the returned map doesn't contain all requested {@code keys} then the entries it does contain
   * will be cached and {@code getAll} will return the partial results. If the returned map contains
   * extra keys not present in {@code keys} then all returned entries will be cached, but only the
   * entries for {@code keys} will be returned from {@code getAll}.
   * <p>
   * This method should be overriden when bulk retrieval is significantly more efficient than many
   * individual lookups. Note that {@link LoadingCache#getAll} will defer to individual calls to
   * {@link LoadingCache#get} if this method is not overriden.
   *
   * @param keys the unique, non-null keys whose values should be loaded
   * @return a map from each key in {@code keys} to the value associated with that key; <b>may not
   *         contain null values</b>
   * @throws RuntimeException or Error, in which case the mappings are unchanged
   */
  @Nonnull
  default Map<K, V> loadAll(@Nonnull Iterable<? extends K> keys) {
    throw new UnsupportedOperationException();
  }

  /**
   * Asynchronously computes or retrieves the value corresponding to {@code key}.
   *
   * @param key the non-null key whose value should be loaded
   * @param executor the executor that asynchronously loads the entry
   * @return the future value associated with {@code key} or {@code null} if not computable
   */
  @CheckForNull
  default CompletableFuture<V> asyncLoad(@Nonnull K key, @Nonnull Executor executor) {
    requireNonNull(key);
    requireNonNull(executor);
    return CompletableFuture.supplyAsync(() -> load(key), executor);
  }

  /**
   * Asynchronously computes or retrieves the values corresponding to {@code keys}. This method is
   * called by {@link AsyncLoadingCache#getAll}.
   * <p>
   * If the returned map doesn't contain all requested {@code keys} then the entries it does contain
   * will be cached and {@code getAll} will return the partial results. If the returned map contains
   * extra keys not present in {@code keys} then all returned entries will be cached, but only the
   * entries for {@code keys} will be returned from {@code getAll}.
   * <p>
   * This method should be overridden when bulk retrieval is significantly more efficient than many
   * individual lookups. Note that {@link AsyncLoadingCache#getAll} will defer to individual calls
   * to {@link AsyncLoadingCache#get} if this method is not overridden.
   *
   * @param keys the unique, non-null keys whose values should be loaded
   * @param executor the executor that with asynchronously loads the entries
   * @return a future containing the map from each key in {@code keys} to the value associated with
   *         that key; <b>may not contain null values</b>
   */
  @Nonnull
  default CompletableFuture<Map<K, V>> asyncLoadAll(
      Iterable<? extends K> keys, @Nonnull Executor executor) {
    requireNonNull(keys);
    requireNonNull(executor);
    return CompletableFuture.supplyAsync(() -> loadAll(keys), executor);
  }

  /**
   * Computes or retrieves a replacement value corresponding to an already-cached {@code key}. If
   * the replacement value is not found then the mapping will be removed if {@code null} is
   * returned. This method is called when an existing cache entry is refreshed by
   * {@link Caffeine#refreshAfterWrite}, or through a call to {@link LoadingCache#refresh}.
   * <p>
   * <b>Note:</b> <i>all exceptions thrown by this method will be logged and then swallowed</i>.
   *
   * @param key the non-null key whose value should be loaded
   * @param oldValue the non-null old value corresponding to {@code key}
   * @return the new value associated with {@code key}, or {@code null} if the mapping is to be
   *         removed
   * @throws RuntimeException or Error, in which case the mapping is unchanged
   */
  @CheckForNull
  default V reload(@Nonnull K key, @Nonnull V oldValue) {
    return load(key);
  }
}
