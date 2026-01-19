/*
 * Copyright 2025 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.toList;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.testing.FutureSubject;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The test cases for the Javadoc external code snippets.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"Convert2MethodRef",
    "NewClassNamingConvention", "PMD.LambdaCanBeMethodReference"})
public final class Snippets {

  @Test
  @SuppressWarnings({"NullAway", "SystemOut"})
  public void builder() {
    // @start region=builder
    LoadingCache<Key, Graph> graphs = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .removalListener((Key key, Graph graph, RemovalCause cause) ->
            System.out.printf("Key %s was removed (%s)%n", key, cause))
        .build(key -> createExpensiveGraph(key));
    // @end region=builder

    assertThat(graphs.get(Key.INSTANCE)).isEqualTo(Graph.INSTANCE);
  }

  @Test
  public void loader_basic() {
    // @start region=loader_basic
    CacheLoader<Key, Graph> loader = key -> createExpensiveGraph(key);
    LoadingCache<Key, Graph> cache = Caffeine.newBuilder().build(loader);
    // @end region=loader_basic

    assertThat(cache.get(Key.INSTANCE)).isEqualTo(Graph.INSTANCE);
  }

  @Test
  public void loader_bulk() {
    // @start region=loader_bulk
    CacheLoader<Key, Graph> loader = CacheLoader.bulk(keys -> createExpensiveGraphs(keys));
    LoadingCache<Key, Graph> cache = Caffeine.newBuilder().build(loader);
    // @end region=loader_bulk

    assertThat(cache.get(Key.INSTANCE)).isEqualTo(Graph.INSTANCE);
  }

  @Test
  public void asyncLoader_basic() {
    // @start region=asyncLoader_basic
    AsyncCacheLoader<Key, Graph> loader = (key, executor) ->
        createExpensiveGraphAsync(key, executor);
    AsyncLoadingCache<Key, Graph> cache = Caffeine.newBuilder().buildAsync(loader);
    // @end region=asyncLoader_basic

    assertThat(cache.get(Key.INSTANCE)).succeedsWith(Graph.INSTANCE);
  }

  @Test
  public void asyncLoader_bulk_sync() {
    // @start region=asyncLoader_bulk_sync
    AsyncCacheLoader<Key, Graph> loader = AsyncCacheLoader.bulk(
        keys -> createExpensiveGraphs(keys));
    AsyncLoadingCache<Key, Graph> cache = Caffeine.newBuilder().buildAsync(loader);
    // @end region=asyncLoader_bulk_sync

    assertThat(cache.get(Key.INSTANCE)).succeedsWith(Graph.INSTANCE);
  }

  @Test
  public void asyncLoader_bulk_async() {
    // @start region=asyncLoader_bulk_async
    AsyncCacheLoader<Key, Graph> loader = AsyncCacheLoader.bulk(
        (keys, executor) -> createExpensiveGraphsAsync(keys, executor));
    AsyncLoadingCache<Key, Graph> cache = Caffeine.newBuilder().buildAsync(loader);
    // @end region=asyncLoader_bulk_async

    FutureSubject.assertThat(cache.get(Key.INSTANCE)).succeedsWith(Graph.INSTANCE);
  }

  @Test
  @SuppressWarnings("TimeZoneUsage")
  public void expiry() {
    // @start region=expiry
    LoadingCache<Key, Graph> cache = Caffeine.newBuilder()
        .expireAfter(Expiry.creating((Key key, Graph graph) ->
            Duration.between(Instant.now(), graph.createdOn().plusHours(5))))
        .build(key -> createExpensiveGraph(key));
    // @end region=expiry

    assertThat(cache.get(Key.INSTANCE)).isEqualTo(Graph.INSTANCE);
  }

  @Test
  @SuppressWarnings("TimeZoneUsage")
  public void expiry_creating() {
    // @start region=expiry_creating
    Expiry<Key, Graph> expiry = Expiry.creating((key, graph) ->
        Duration.between(Instant.now(), graph.createdOn().plusHours(5)));
    // @end region=expiry_creating

    LoadingCache<Key, Graph> cache = Caffeine.newBuilder()
        .expireAfter(expiry)
        .build(key -> createExpensiveGraph(key));
    assertThat(cache.get(Key.INSTANCE)).isEqualTo(Graph.INSTANCE);
  }

  @Test
  @SuppressWarnings("TimeZoneUsage")
  public void expiry_writing() {
    // @start region=expiry_writing
    Expiry<Key, Graph> expiry = Expiry.writing((key, graph) ->
        Duration.between(Instant.now(), graph.modifiedOn().plusHours(5)));
    // @end region=expiry_writing

    LoadingCache<Key, Graph> cache = Caffeine.newBuilder()
        .expireAfter(expiry)
        .build(key -> createExpensiveGraph(key));
    assertThat(cache.get(Key.INSTANCE)).isEqualTo(Graph.INSTANCE);
  }

  @Test
  @SuppressWarnings("TimeZoneUsage")
  public void expiry_accessing() {
    // @start region=expiry_accessing
    Expiry<Key, Graph> expiry = Expiry.accessing((key, graph) ->
        graph.isDirected() ? Duration.ofHours(1) : Duration.ofHours(3));
    // @end region=expiry_accessing

    LoadingCache<Key, Graph> cache = Caffeine.newBuilder()
        .expireAfter(expiry)
        .build(key -> createExpensiveGraph(key));
    assertThat(cache.get(Key.INSTANCE)).isEqualTo(Graph.INSTANCE);
  }

  @Test
  @SuppressWarnings("CollectorMutability")
  public void eviction_coldest() {
    Cache<K, Graph> cache = Caffeine.newBuilder()
        .maximumSize(1000)
        .build();

    // @start region=eviction_coldest
    List<K> tenColdestKeys = cache.policy().eviction().orElseThrow()
        .coldest(stream -> stream.map(Map.Entry::getKey).limit(10).collect(toList()));
    // @end region=eviction_coldest

    assertThat(tenColdestKeys).isEmpty();
  }

  @Test
  @SuppressWarnings("CollectorMutability")
  public void eviction_hottest() {
    Cache<K, Graph> cache = Caffeine.newBuilder()
        .maximumSize(1000)
        .build();

    // @start region=eviction_hottest
    List<K> tenHottestKeys = cache.policy().eviction().orElseThrow()
        .hottest(stream -> stream.map(Map.Entry::getKey).limit(10).collect(toList()));
    // @end region=eviction_hottest

    assertThat(tenHottestKeys).isEmpty();
  }

  @Test
  @SuppressWarnings("CollectorMutability")
  public void expireFixed_oldest() {
    Cache<K, Graph> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofDays(1))
        .build();

    // @start region=expireFixed_oldest
    List<K> tenOldestKeys = cache.policy().expireAfterWrite().orElseThrow()
        .oldest(stream -> stream.map(Map.Entry::getKey).limit(10).collect(toList()));
    // @end region=expireFixed_oldest

    assertThat(tenOldestKeys).isEmpty();
  }

  @Test
  @SuppressWarnings("CollectorMutability")
  public void expireFixed_youngest() {
    Cache<K, Graph> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofDays(1))
        .build();

    // @start region=expireFixed_youngest
    List<K> tenYoungestKeys = cache.policy().expireAfterWrite().orElseThrow()
        .youngest(stream -> stream.map(Map.Entry::getKey).limit(10).collect(toList()));
    // @end region=expireFixed_youngest

    assertThat(tenYoungestKeys).isEmpty();
  }

  @Test
  @SuppressWarnings("CollectorMutability")
  public void expireVar_oldest() {
    Cache<K, Graph> cache = Caffeine.newBuilder()
        .expireAfter(Expiry.creating((key, value) -> Duration.ofDays(1)))
        .build();

    // @start region=expireVar_oldest
    List<K> tenOldestKeys = cache.policy().expireVariably().orElseThrow()
        .oldest(stream -> stream.map(Map.Entry::getKey).limit(10).collect(toList()));
    // @end region=expireVar_oldest

    assertThat(tenOldestKeys).isEmpty();
  }

  @Test
  @SuppressWarnings("CollectorMutability")
  public void expireVar_youngest() {
    Cache<K, Graph> cache = Caffeine.newBuilder()
        .expireAfter(Expiry.creating((key, value) -> Duration.ofDays(1)))
        .build();

    // @start region=expireVar_youngest
    List<K> tenYoungestKeys = cache.policy().expireVariably().orElseThrow()
        .youngest(stream -> stream.map(Map.Entry::getKey).limit(10).collect(toList()));
    // @end region=expireVar_youngest

    assertThat(tenYoungestKeys).isEmpty();
  }

  @SuppressFBWarnings("UP_UNUSED_PARAMETER")
  @SuppressWarnings({"unused", "UnusedVariable"})
  private static CompletableFuture<ImmutableMap<? extends Key, Graph>> createExpensiveGraphsAsync(
      Set<? extends Key> keys, Executor executor) {
    return CompletableFuture.completedFuture(createExpensiveGraphs(keys));
  }

  @SuppressFBWarnings("UP_UNUSED_PARAMETER")
  @SuppressWarnings({"unused", "UnusedVariable"})
  private static CompletableFuture<Graph> createExpensiveGraphAsync(Key key, Executor executor) {
    return CompletableFuture.completedFuture(Graph.INSTANCE);
  }

  private static ImmutableMap<? extends Key, Graph> createExpensiveGraphs(Set<? extends Key> keys) {
    return Maps.toMap(keys, key -> Graph.INSTANCE);
  }

  @SuppressFBWarnings("UP_UNUSED_PARAMETER")
  @SuppressWarnings({"unused", "UnusedVariable"})
  private static Graph createExpensiveGraph(Key key) {
    return Graph.INSTANCE;
  }

  @SuppressWarnings("ClassNamedLikeTypeParameter")
  private enum K { INSTANCE }

  private enum Key { INSTANCE }

  @SuppressWarnings("MethodCanBeStatic")
  private enum Graph {
    INSTANCE;

    private OffsetDateTime createdOn() {
      return OffsetDateTime.MIN;
    }
    private OffsetDateTime modifiedOn() {
      return OffsetDateTime.MIN;
    }
    private boolean isDirected() {
      return true;
    }
  }
}
