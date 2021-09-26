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
import java.util.concurrent.CompletionException;

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
public interface LoadingCache<K extends Object, V extends Object> extends Cache<K, V> {

  /**
   * Returns the value associated with the {@code key} in this cache, obtaining that value from
   * {@link CacheLoader#load(Object)} if necessary.
   * <p>
   * If another call to {@link #get} is currently loading the value for the {@code key}, this thread
   * simply waits for that thread to finish and returns its loaded value. Note that multiple threads
   * can concurrently load values for distinct keys.
   * <p>
   * If the specified key is not already associated with a value, attempts to compute its value and
   * enters it into this cache unless {@code null}. The entire method invocation is performed
   * atomically, so the function is applied at most once per key. Some attempted update operations
   * on this cache by other threads may be blocked while the computation is in progress, so the
   * computation should be short and simple, and must not attempt to update any other mappings of
   * this cache.
   *
   * @param key key with which the specified value is to be associated
   * @return the current (existing or computed) value associated with the specified key, or null if
   *         the computed value is null
   * @throws NullPointerException if the specified key is null
   * @throws IllegalStateException if the computation detectably attempts a recursive update to this
   *         cache that would otherwise never complete
   * @throws CompletionException if a checked exception was thrown while loading the value
   * @throws RuntimeException or Error if the {@link CacheLoader} does so, in which case the mapping
   *         is left unestablished
   */
  V get(K key);

  /**
   * Returns a map of the values associated with the {@code keys}, creating or retrieving those
   * values if necessary. The returned map contains entries that were already cached, combined with
   * the newly loaded entries; it will never contain null keys or values.
   * <p>
   * Caches loaded by a {@link CacheLoader} will issue a single request to
   * {@link CacheLoader#loadAll} for all keys which are not already present in the cache. All
   * entries returned by {@link CacheLoader#loadAll} will be stored in the cache, over-writing any
   * previously cached values. If another call to {@link #get} tries to load the value for a key in
   * {@code keys}, implementations may either have that thread load the entry or simply wait for
   * this thread to finish and returns the loaded value. In the case of overlapping non-blocking
   * loads, the last load to complete will replace the existing entry. Note that multiple threads
   * can concurrently load values for distinct keys.
   * <p>
   * Note that duplicate elements in {@code keys}, as determined by {@link Object#equals}, will be
   * ignored.
   *
   * @param keys the keys whose associated values are to be returned
   * @return the unmodifiable mapping of keys to values for the specified keys in this cache
   * @throws NullPointerException if the specified collection is null or contains a null element
   * @throws CompletionException if a checked exception was thrown while loading the value
   * @throws RuntimeException or Error if the {@link CacheLoader} does so, if
   *         {@link CacheLoader#loadAll} returns {@code null}, or returns a map containing null keys
   *         or values. In all cases, the mapping is left unestablished.
   */
  Map<K, V> getAll(Iterable<? extends K> keys);

  /**
   * Loads a new value for the {@code key}, asynchronously. While the new value is loading the
   * previous value (if any) will continue to be returned by {@code get(key)} unless it is evicted.
   * If the new value is loaded successfully it will replace the previous value in the cache; if an
   * exception is thrown while refreshing the previous value will remain, <i>and the exception will
   * be logged (using {@link System.Logger}) and swallowed</i>.
   * <p>
   * Caches loaded by a {@link CacheLoader} will call {@link CacheLoader#reload} if the cache
   * currently contains a value for the {@code key}, and {@link CacheLoader#load} otherwise. Loading
   * is asynchronous by delegating to the default executor.
   * <p>
   * Returns an existing future without doing anything if another thread is currently loading the
   * value for {@code key}.
   *
   * @param key key with which a value may be associated
   * @return the future that is loading the value
   * @throws NullPointerException if the specified key is null
   */
  CompletableFuture<V> refresh(K key);

  /**
   * Loads a new value for each {@code key}, asynchronously. While the new value is loading the
   * previous value (if any) will continue to be returned by {@code get(key)} unless it is evicted.
   * If the new value is loaded successfully it will replace the previous value in the cache; if an
   * exception is thrown while refreshing the previous value will remain, <i>and the exception will
   * be logged (using {@link System.Logger}) and swallowed</i>. If another thread is currently
   * loading the value for {@code key}, then does not perform an additional load.
   * <p>
   * Caches loaded by a {@link CacheLoader} will call {@link CacheLoader#reload} if the cache
   * currently contains a value for the {@code key}, and {@link CacheLoader#load} otherwise. Loading
   * is asynchronous by delegating to the default executor.
   *
   * @param keys the keys whose associated values are to be returned
   * @return the future containing an unmodifiable mapping of keys to values for the specified keys
   *         that are loading the values
   * @throws NullPointerException if the specified collection is null or contains a null element
   */
  CompletableFuture<Map<K, V>> refreshAll(Iterable<? extends K> keys);
}
