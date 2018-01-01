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
import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A semi-persistent mapping from keys to values. Values are automatically loaded by the cache
 * asynchronously, and are stored in the cache until either evicted or manually invalidated.
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

  /**
   * Returns the future associated with {@code key} in this cache, or {@code null} if there is no
   * cached future for {@code key}.
   *
   * @param key key whose associated value is to be returned
   * @return the current (existing or computed) future value to which the specified key is mapped,
   *         or {@code null} if this map contains no mapping for the key
   * @throws NullPointerException if the specified key is null
   */
  @Nullable
  CompletableFuture<V> getIfPresent(@Nonnull Object key);

  /**
   * Returns the future associated with {@code key} in this cache, obtaining that value from
   * {@code mappingFunction} if necessary. This method provides a simple substitute for the
   * conventional "if cached, return; otherwise create, cache and return" pattern.
   * <p>
   * If the specified key is not already associated with a value, attempts to compute its value
   * asynchronously and enters it into this cache unless {@code null}. The entire method invocation
   * is performed atomically, so the function is applied at most once per key. If the asynchronous
   * computation fails, the entry will be automatically removed from this cache.
   * <p>
   * <b>Warning:</b> as with {@link CacheLoader#load}, {@code mappingFunction} <b>must not</b>
   * attempt to update any other mappings of this cache.
   *
   * @param key key with which the specified value is to be associated
   * @param mappingFunction the function to asynchronously compute a value
   * @return the current (existing or computed) future value associated with the specified key
   * @throws NullPointerException if the specified key or mappingFunction is null
   */
  @Nonnull
  CompletableFuture<V> get(@Nonnull K key,
      @Nonnull Function<? super K, ? extends V> mappingFunction);

  /**
   * Returns the future associated with {@code key} in this cache, obtaining that value from
   * {@code mappingFunction} if necessary. This method provides a simple substitute for the
   * conventional "if cached, return; otherwise create, cache and return" pattern.
   * <p>
   * If the specified key is not already associated with a value, attempts to compute its value
   * asynchronously and enters it into this cache unless {@code null}. The entire method invocation
   * is performed atomically, so the function is applied at most once per key. If the asynchronous
   * computation fails, the entry will be automatically removed from this cache.
   * <p>
   * <b>Warning:</b> as with {@link CacheLoader#load}, {@code mappingFunction} <b>must not</b>
   * attempt to update any other mappings of this cache.
   *
   * @param key key with which the specified value is to be associated
   * @param mappingFunction the function to asynchronously compute a value
   * @return the current (existing or computed) future value associated with the specified key
   * @throws NullPointerException if the specified key or mappingFunction is null, or if the
   *         future returned by the mappingFunction is null
   * @throws RuntimeException or Error if the mappingFunction does when constructing the future,
   *         in which case the mapping is left unestablished
   */
  @Nonnull
  CompletableFuture<V> get(@Nonnull K key,
      @Nonnull BiFunction<? super K, Executor, CompletableFuture<V>> mappingFunction);

  /**
   * Returns the future associated with {@code key} in this cache, obtaining that value from
   * {@link CacheLoader#asyncLoad} if necessary. If the asynchronous computation fails, the entry
   * will be automatically removed from this cache.
   * <p>
   * If the specified key is not already associated with a value, attempts to compute its value
   * asynchronously and enters it into this cache unless {@code null}. The entire method invocation
   * is performed atomically, so the function is applied at most once per key.
   *
   * @param key key with which the specified value is to be associated
   * @return the current (existing or computed) future value associated with the specified key
   * @throws NullPointerException if the specified key is null or if the future returned by the
   *         {@link AsyncCacheLoader} is null
   * @throws RuntimeException or Error if the {@link CacheLoader} does when constructing the future,
   *         in which case the mapping is left unestablished
   */
  @Nonnull
  CompletableFuture<V> get(@Nonnull K key);

  /**
   * Returns the future of a map of the values associated with {@code keys}, creating or retrieving
   * those values if necessary. The returned map contains entries that were already cached, combined
   * with newly loaded entries; it will never contain null keys or values. If the any of the
   * asynchronous computations fail, those entries will be automatically removed from this cache.
   * <p>
   * Caches loaded by a {@link CacheLoader} supporting bulk loading will issue a single request to
   * {@link CacheLoader#asyncLoadAll} for all keys which are not already present in the cache. If
   * another call to {@link #get} tries to load the value for a key in {@code keys}, that thread
   * simply waits for this computation to finish and returns the loaded value. Caches that do not
   * use a {@link CacheLoader} with an optimized bulk load implementation will sequentially load
   * each key by making individual {@link CacheLoader#asyncLoad} calls. Note that multiple threads
   * can concurrently load values for distinct keys.
   * <p>
   * Note that duplicate elements in {@code keys}, as determined by {@link Object#equals}, will be
   * ignored.
   *
   * @param keys the keys whose associated values are to be returned
   * @return the future containing an unmodifiable mapping of keys to values for the specified keys
   *         in this cache
   * @throws NullPointerException if the specified collection is null or contains a null element, or
   *         if the future returned by the {@link AsyncCacheLoader} is null
   * @throws RuntimeException or Error if the {@link CacheLoader} does so, if
   *         {@link CacheLoader#asyncLoadAll} returns {@code null}, or fails when constructing the
   *         future, in which case the mapping is left unestablished
   */
  @Nonnull
  CompletableFuture<Map<K, V>> getAll(@Nonnull Iterable<? extends K> keys);

  /**
   * Associates {@code value} with {@code key} in this cache. If the cache previously contained a
   * value associated with {@code key}, the old value is replaced by {@code value}. If the
   * asynchronous computation fails, the entry will be automatically removed.
   * <p>
   * Prefer {@link #get(Object, Function)} when using the conventional "if cached, return; otherwise
   * create, cache and return" pattern.
   *
   * @param key key with which the specified value is to be associated
   * @param valueFuture value to be associated with the specified key
   * @throws NullPointerException if the specified key or value is null
   */
  void put(@Nonnull K key, @Nonnull CompletableFuture<V> valueFuture);

  /**
   * Returns a view of the entries stored in this cache as a synchronous {@link LoadingCache}. A
   * mapping is not present if the value is currently being loaded. Modifications made to the
   * synchronous cache directly affect the asynchronous cache. If a modification is made to a
   * mapping that is currently loading, the operation blocks until the computation completes.
   *
   * @return a thread-safe synchronous view of this cache
   */
  @Nonnull
  LoadingCache<K, V> synchronous();
}
