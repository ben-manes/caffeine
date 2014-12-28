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
import java.util.concurrent.Executor;

/**
 * Computes or retrieves values, based on a key, for use in populating a {@link LoadingCache}.
 * <p>
 * Most implementations will only need to implement {@link #load}. Other methods may be
 * overridden as desired.
 * <p>
 * Usage example:
 * <pre>{@code
 *   CacheLoader<Key, Graph> loader = new CacheLoader<Key, Graph>() {
 *     public Graph load(Key key) throws AnyException {
 *       return createExpensiveGraph(key);
 *     }
 *   };
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
   * @return the value associated with {@code key}
   */
  V load(K key);

  /**
   * Computes or retrieves the values corresponding to {@code keys}. This method is called by
   * {@link LoadingCache#getAll}.
   * <p>
   * If the returned map doesn't contain all requested {@code keys} then the entries it does contain
   * will be cached, but {@code getAll} will throw an exception. If the returned map contains extra
   * keys not present in {@code keys} then all returned entries will be cached, but only the entries
   * for {@code keys} will be returned from {@code getAll}.
   * <p>
   * This method should be overriden when bulk retrieval is significantly more efficient than many
   * individual lookups. Note that {@link LoadingCache#getAll} will defer to individual calls to
   * {@link LoadingCache#get} if this method is not overriden.
   *
   * @param keys the unique, non-null keys whose values should be loaded
   * @return a map from each key in {@code keys} to the value associated with that key; <b>may not
   *         contain null values</b>
   * @throws UnsupportedOperationException if bulk loading is not implemented
   */
  default Map<K, V> loadAll(Iterable<? extends K> keys) {
    throw new UnsupportedOperationException();
  }

  /**
   * Asynchronously computes or retrieves the value corresponding to {@code key}.
   *
   * @param key the non-null key whose value should be loaded
   * @return the future value associated with {@code key}
   */
  default CompletableFuture<V> asyncLoad(K key, Executor executor) {
    return CompletableFuture.supplyAsync(() -> load(key), executor);
  }

  /**
   * Asynchronously computes or retrieves the values corresponding to {@code keys}. This method is
   * called by {@link AsyncLoadingCache#getAll}.
   * <p>
   * If the returned map doesn't contain all requested {@code keys} then the entries it does contain
   * will be cached, but {@code getAll} will throw an exception. If the returned map contains extra
   * keys not present in {@code keys} then all returned entries will be cached, but only the entries
   * for {@code keys} will be returned from {@code getAll}.
   * <p>
   * This method should be overriden when bulk retrieval is significantly more efficient than many
   * individual lookups. Note that {@link AsyncLoadingCache#getAll} will defer to individual calls
   * to {@link AsyncLoadingCache#get} if this method is not overriden.
   *
   * @param keys the unique, non-null keys whose values should be loaded
   * @return a future containing the map from each key in {@code keys} to the value associated with
   *         that key; <b>may not contain null values</b>
   * @throws UnsupportedOperationException if bulk loading is not implemented
   */
  default CompletableFuture<Map<K, V>> asyncLoadAll(K key, Executor executor) {
    throw new UnsupportedOperationException();
  }

  /**
   * Computes or retrieves a replacement value corresponding to an already-cached {@code key}. This
   * method is called when an existing cache entry is refreshed by
   * {@link Caffeine#refreshAfterWrite}, or through a call to {@link LoadingCache#refresh}.
   * <p>
   * <b>Note:</b> <i>all exceptions thrown by this method will be logged and then swallowed</i>.
   *
   * @param key the non-null key whose value should be loaded
   * @param oldValue the non-null old value corresponding to {@code key}
   * @return the new value associated with {@code key}, or null if none
   * @throws RuntimeException or Error, in which case the mapping is unchanged
   */
  default V refresh(K key, V oldValue) {
    return load(key);
  }
}
