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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.google.errorprone.annotations.CheckReturnValue;

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
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "FunctionalInterfaceMethodChanged"})
public interface CacheLoader<K extends Object, V extends Object> extends AsyncCacheLoader<K, V> {

  /**
   * Computes or retrieves the value corresponding to {@code key}.
   * <p>
   * <b>Warning:</b> loading <b>must not</b> attempt to update any mappings of this cache directly.
   *
   * @param key the non-null key whose value should be loaded
   * @return the value associated with {@code key} or {@code null} if not found
   * @throws Exception or Error, in which case the mapping is unchanged
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *         treated like any other {@code Exception} in all respects except that, when it is
   *         caught, the thread's interrupt status is set
   */
  @Nullable
  V load(K key) throws Exception;

  /**
   * Computes or retrieves the values corresponding to {@code keys}. This method is called by
   * {@link LoadingCache#getAll}.
   * <p>
   * If the returned map doesn't contain all requested {@code keys} then the entries it does contain
   * will be cached and {@code getAll} will return the partial results. If the returned map contains
   * extra keys not present in {@code keys} then all returned entries will be cached, but only the
   * entries for {@code keys} will be returned from {@code getAll}.
   * <p>
   * This method should be overridden when bulk retrieval is significantly more efficient than many
   * individual lookups. Note that {@link LoadingCache#getAll} will defer to individual calls to
   * {@link LoadingCache#get} if this method is not overridden.
   * <p>
   * <b>Warning:</b> loading <b>must not</b> attempt to update any mappings of this cache directly.
   *
   * @param keys the unique, non-null keys whose values should be loaded
   * @return a map from each key in {@code keys} to the value associated with that key; <b>may not
   *         contain null values</b>
   * @throws Exception or Error, in which case the mappings are unchanged
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *         treated like any other {@code Exception} in all respects except that, when it is
   *         caught, the thread's interrupt status is set
   */
  default Map<? extends K, ? extends V> loadAll(Set<? extends K> keys) throws Exception {
    throw new UnsupportedOperationException();
  }

  /**
   * Asynchronously computes or retrieves the value corresponding to {@code key}.
   *
   * @param key the non-null key whose value should be loaded
   * @param executor the executor that asynchronously loads the entry
   * @return the future value associated with {@code key}
   */
  @Override
  default CompletableFuture<? extends V> asyncLoad(K key, Executor executor) {
    requireNonNull(key);
    requireNonNull(executor);
    return CompletableFuture.supplyAsync(() -> {
      try {
        return load(key);
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    }, executor);
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
  @Override
  default CompletableFuture<? extends Map<? extends K, ? extends V>> asyncLoadAll(
      Set<? extends K> keys, Executor executor) {
    requireNonNull(keys);
    requireNonNull(executor);
    return CompletableFuture.supplyAsync(() -> {
      try {
        return loadAll(keys);
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    }, executor);
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
   * @throws Exception or Error, in which case the mapping is unchanged
   * @throws InterruptedException if this method is interrupted. {@code InterruptedException} is
   *         treated like any other {@code Exception} in all respects except that, when it is
   *         caught, the thread's interrupt status is set
   */
  default @Nullable V reload(K key, V oldValue) throws Exception {
    return load(key);
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
   */
  @Override
  default CompletableFuture<? extends V> asyncReload(
      K key, V oldValue, Executor executor) throws Exception {
    requireNonNull(key);
    requireNonNull(executor);
    return CompletableFuture.supplyAsync(() -> {
      try {
        return reload(key, oldValue);
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    }, executor);
  }

  /**
   * Returns a cache loader that delegates to the supplied mapping function for retrieving the
   * values. Note that {@link #load} will silently discard any additional mappings loaded when
   * retrieving the {@code key} prior to returning to the value to the cache.
   * <p>
   * Usage example:
   * <pre>{@code
   *   CacheLoader<Key, Graph> loader = CacheLoader.bulk(keys -> createExpensiveGraphs(keys));
   *   LoadingCache<Key, Graph> cache = Caffeine.newBuilder().build(loader);
   * }</pre>
   *
   * @param <K> the key type
   * @param <V> the value type
   * @param mappingFunction the function to compute the values
   * @return a cache loader that delegates to the supplied {@code mappingFunction}
   * @throws NullPointerException if the mappingFunction is null
   */
  @CheckReturnValue
  static <K extends Object, V extends Object> CacheLoader<K, V> bulk(
      Function<? super Set<? extends K>, ? extends Map<? extends K, ? extends V>> mappingFunction) {
    requireNonNull(mappingFunction);
    return new CacheLoader<K, V>() {
      @Override public @Nullable V load(K key) {
        return loadAll(Set.of(key)).get(key);
      }
      @Override public Map<? extends K, ? extends V> loadAll(Set<? extends K> keys) {
        return mappingFunction.apply(keys);
      }
    };
  }
}
