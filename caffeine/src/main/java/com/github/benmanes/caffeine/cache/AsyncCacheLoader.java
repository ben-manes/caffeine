/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.checkerframework.checker.nullness.qual.NonNull;

import com.google.errorprone.annotations.CheckReturnValue;

/**
 * Computes or retrieves values asynchronously, based on a key, for use in populating a
 * {@link AsyncLoadingCache}.
 * <p>
 * Most implementations will only need to implement {@link #asyncLoad}. Other methods may be
 * overridden as desired.
 * <p>
 * Usage example:
 * <pre>{@code
 *   AsyncCacheLoader<Key, Graph> loader = (key, executor) ->
 *       createExpensiveGraphAsync(key, executor);
 *   AsyncLoadingCache<Key, Graph> cache = Caffeine.newBuilder().buildAsync(loader);
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@FunctionalInterface
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
public interface AsyncCacheLoader<K extends @NonNull Object, V extends @NonNull Object> {

  /**
   * Asynchronously computes or retrieves the value corresponding to {@code key}.
   *
   * @param key the non-null key whose value should be loaded
   * @param executor the executor with which the entry is asynchronously loaded
   * @return the future value associated with {@code key}
   * @throws Exception or Error, in which case the mapping is unchanged
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *         treated like any other {@code Exception} in all respects except that, when it is
   *         caught, the thread's interrupt status is set
   */
  CompletableFuture<? extends V> asyncLoad(K key, Executor executor) throws Exception;

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
   * @param executor the executor with which the entries are asynchronously loaded
   * @return a future containing the map from each key in {@code keys} to the value associated with
   *         that key; <b>may not contain null values</b>
   * @throws Exception or Error, in which case the mappings are unchanged
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *         treated like any other {@code Exception} in all respects except that, when it is
   *         caught, the thread's interrupt status is set
   */
  default CompletableFuture<? extends Map<? extends K, ? extends V>> asyncLoadAll(
      Set<? extends K> keys, Executor executor) throws Exception {
    throw new UnsupportedOperationException();
  }

  /**
   * Asynchronously computes or retrieves a replacement value corresponding to an already-cached
   * {@code key}. If the replacement value is not found then the mapping will be removed if
   * {@code null} is computed. This method is called when an existing cache entry is refreshed by
   * {@link Caffeine#refreshAfterWrite}, or through a call to {@link LoadingCache#refresh}.
   * <p>
   * <b>Note:</b> <i>all exceptions thrown by this method will be logged and then swallowed</i>.
   *
   * @param key the non-null key whose value should be loaded
   * @param oldValue the non-null old value corresponding to {@code key}
   * @param executor the executor with which the entry is asynchronously loaded
   * @return a future containing the new value associated with {@code key}, or containing
   *         {@code null} if the mapping is to be removed
   * @throws Exception or Error, in which case the mapping is unchanged
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *         treated like any other {@code Exception} in all respects except that, when it is
   *         caught, the thread's interrupt status is set
   */
  default CompletableFuture<? extends V> asyncReload(
      K key, V oldValue, Executor executor) throws Exception {
    return asyncLoad(key, executor);
  }

  /**
   * Returns an asynchronous cache loader that delegates to the supplied mapping function for
   * retrieving the values. Note that {@link #asyncLoad} will discard any additional mappings
   * loaded when retrieving the {@code key} prior to returning to the value to the cache.
   * <p>
   * Usage example:
   * <pre>{@code
   *   AsyncCacheLoader<Key, Graph> loader = AsyncCacheLoader.bulk(
   *       keys -> createExpensiveGraphs(keys));
   *   AsyncLoadingCache<Key, Graph> cache = Caffeine.newBuilder().buildAsync(loader);
   * }</pre>
   *
   * @param <K> the key type
   * @param <V> the value type
   * @param mappingFunction the function to asynchronously compute the values
   * @return an asynchronous cache loader that delegates to the supplied {@code mappingFunction}
   * @throws NullPointerException if the mappingFunction is null
   */
  @CheckReturnValue
  static <K extends Object, V extends Object> AsyncCacheLoader<K, V> bulk(
      Function<? super Set<? extends K>, ? extends Map<? extends K, ? extends V>> mappingFunction) {
    return CacheLoader.bulk(mappingFunction);
  }

  /**
   * Returns an asynchronous cache loader that delegates to the supplied mapping function for
   * retrieving the values. Note that {@link #asyncLoad} will silently discard any additional
   * mappings loaded when retrieving the {@code key} prior to returning to the value to the cache.
   * <p>
   * Usage example:
   * <pre>{@code
   *   AsyncCacheLoader<Key, Graph> loader = AsyncCacheLoader.bulk(
   *       (keys, executor) -> createExpensiveGraphs(keys, executor));
   *   AsyncLoadingCache<Key, Graph> cache = Caffeine.newBuilder().buildAsync(loader);
   * }</pre>
   *
   * @param <K> the key type
   * @param <V> the value type
   * @param mappingFunction the function to asynchronously compute the values
   * @return an asynchronous cache loader that delegates to the supplied {@code mappingFunction}
   * @throws NullPointerException if the mappingFunction is null
   */
  @CheckReturnValue
  static <K extends Object, V extends Object> AsyncCacheLoader<K, V> bulk(
      BiFunction<? super Set<? extends K>, ? super Executor,
      ? extends CompletableFuture<? extends Map<? extends K, ? extends V>>> mappingFunction) {
    requireNonNull(mappingFunction);
    return new AsyncCacheLoader<>() {
      @Override public CompletableFuture<V> asyncLoad(K key, Executor executor) {
        return asyncLoadAll(Set.of(key), executor)
            .thenApply(results -> results.get(key));
      }
      @Override public CompletableFuture<Map<K, V>> asyncLoadAll(
          Set<? extends K> keys, Executor executor) {
        requireNonNull(keys);
        requireNonNull(executor);
        @SuppressWarnings("unchecked")
        var future = (CompletableFuture<Map<K, V>>) mappingFunction.apply(keys, executor);
        return future;
      }
    };
  }
}
