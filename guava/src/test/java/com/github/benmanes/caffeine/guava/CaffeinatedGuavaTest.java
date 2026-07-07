/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.guava;

import static com.github.benmanes.caffeine.guava.CaffeinatedGuava.caffeinate;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Spliterator.CONCURRENT;
import static java.util.Spliterator.DISTINCT;
import static java.util.Spliterator.NONNULL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.CaffeinatedLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.ExternalBulkLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.ExternalSingleLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.InternalBulkLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.InternalSingleLoader;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.testing.SerializableTester;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class CaffeinatedGuavaTest {

  @Test
  void serializable() {
    SerializableTester.reserialize(CaffeinatedGuava.build(Caffeine.newBuilder()));
    SerializableTester.reserialize(CaffeinatedGuava.build(
        Caffeine.newBuilder(), IdentityLoader.INSTANCE));
    SerializableTester.reserialize(CaffeinatedGuava.build(Caffeine.newBuilder(),
        CacheLoader.from((Function<Object, Object> & Serializable) key -> key)));
  }

  @Test
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  void reflectivelyConstruct() throws ReflectiveOperationException {
    Constructor<?> constructor = CaffeinatedGuava.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  @Test
  void hasMethod_notFound() {
    assertThat(CaffeinatedGuava.hasMethod(CacheLoader.from(k -> k), "abc")).isFalse();
  }

  @ParameterizedTest
  @MethodSource("caches")
  @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION")
  void asMap_get_null(Cache<Integer, Integer> cache) {
    cache.put(1, 2);

    assertThat(cache.asMap().get(null)).isNull();
    assertThat(cache.asMap().containsKey(null)).isFalse();
    assertThat(cache.asMap().containsValue(null)).isFalse();
    assertThat(cache.asMap().remove(null)).isNull();
    assertThat(cache.asMap().remove(null, 2)).isFalse();
    assertThat(cache.asMap().remove(1, null)).isFalse();
    assertThat(cache.asMap().replace(1, null, 3)).isFalse();
    assertThat(cache.asMap().keySet().contains(null)).isFalse();
    assertThat(cache.asMap().values().contains(null)).isFalse();
    assertThat(cache.asMap()).containsExactly(1, 2);
  }

  @ParameterizedTest
  @MethodSource("caches")
  void asMap_entrySet_removeIf_setValue(Cache<String, String> cache) {
    cache.put("a", "1");

    // The view delegates to the cache's removeIf, which (like ConcurrentHashMap and Guava,
    // JDK-8078726) passes the predicate an immutable snapshot, so setValue is unsupported.
    assertThrows(UnsupportedOperationException.class, () ->
        cache.asMap().entrySet().removeIf(entry -> {
          entry.setValue("2");
          return false;
        }));
    assertThat(cache.asMap()).containsExactly("a", "1");
  }

  @Test
  void asMap_spliterator_concurrent() {
    // The views delegate to the cache so the spliterators report CONCURRENT rather than the
    // SIZED default that the forwarding views would otherwise inherit.
    Cache<String, String> cache = CaffeinatedGuava.build(Caffeine.newBuilder());
    cache.put("a", "1");

    assertThat(cache.asMap().keySet().spliterator().characteristics())
        .isEqualTo(DISTINCT | NONNULL | CONCURRENT);
    assertThat(cache.asMap().values().spliterator().characteristics())
        .isEqualTo(NONNULL | CONCURRENT);
    assertThat(cache.asMap().entrySet().spliterator().characteristics())
        .isEqualTo(DISTINCT | NONNULL | CONCURRENT);
  }

  @ParameterizedTest
  @MethodSource("caches")
  void getAllPresent_nullKey(Cache<Integer, Integer> cache) {
    cache.put(1, 2);

    assertThat(cache.getAllPresent(Arrays.asList(1, null, 3))).containsExactly(1, 2);
  }

  @ParameterizedTest
  @MethodSource("caches")
  void invalidateAll_nullKey(Cache<Integer, Integer> cache) {
    cache.put(1, 2);
    cache.put(3, 4);

    // A null element is skipped (as in Guava), not an NPE that leaves a partial removal.
    cache.invalidateAll(Arrays.asList(1, null, 3));
    assertThat(cache.asMap()).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("caches")
  @SuppressFBWarnings("LUI_VACUOUS_ADDALL")
  void entrySet_addAll_empty(Cache<Integer, Integer> cache) {
    // Guava's AbstractCollection.addAll returns false for an empty collection rather than throwing.
    assertThat(cache.asMap().entrySet().addAll(Set.<Map.Entry<Integer, Integer>>of())).isFalse();
  }

  @Test
  void bulkLoad() throws ReflectiveOperationException, ExecutionException {
    var cache = CaffeinatedGuava.build(Caffeine.newBuilder(), new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new IllegalStateException();
      }
      @Override public ImmutableMap<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        return Maps.toMap(ImmutableSet.copyOf(keys), key -> -key);
      }
    });
    var nullBulkLoad = getNullBulkLoad(cache);
    assertThat(nullBulkLoad.get()).isNull();
    assertThat(cache.getAll(ImmutableList.of(1, 2, 3)))
        .isEqualTo(ImmutableMap.of(1, -1, 2, -2, 3, -3));
    assertThat(nullBulkLoad.get()).isNull();
  }

  @Test
  void bulkLoad_nullKey() throws ReflectiveOperationException {
    var cache = CaffeinatedGuava.build(Caffeine.newBuilder(), new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new IllegalStateException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        var result = new HashMap<Integer, Integer>();
        for (var key : keys) {
          result.put(null, -key);
        }
        return result;
      }
    });
    var nullBulkLoad = getNullBulkLoad(cache);
    assertThat(nullBulkLoad.get()).isNull();
    assertThrows(InvalidCacheLoadException.class, () -> cache.getAll(ImmutableList.of(1, 2, 3)));
    assertThat(nullBulkLoad.get()).isNull();
  }

  @Test
  void bulkLoad_nullValue() throws ReflectiveOperationException {
    var cache = CaffeinatedGuava.build(Caffeine.newBuilder(), new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new IllegalStateException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        var result = new HashMap<Integer, Integer>();
        for (var key : keys) {
          result.put(key, null);
        }
        return result;
      }
    });
    var nullBulkLoad = getNullBulkLoad(cache);
    assertThat(nullBulkLoad.get()).isNull();
    assertThrows(InvalidCacheLoadException.class, () -> cache.getAll(ImmutableList.of(1, 2, 3)));
    assertThat(nullBulkLoad.get()).isNull();
  }

  @Test
  void bulkLoad_asyncReloading() throws ExecutionException {
    var cache = CaffeinatedGuava.build(Caffeine.newBuilder(),
        CacheLoader.asyncReloading(new CacheLoader<Integer, Integer>() {
          @Override public Integer load(Integer key) {
            return -key;
          }
        }, Runnable::run));
    assertThat(cache.getAll(ImmutableList.of(1, 2, 3)))
        .isEqualTo(ImmutableMap.of(1, -1, 2, -2, 3, -3));
  }

  @Test
  void bulkLoad_asyncReloading_caffeinate() {
    var cache = Caffeine.newBuilder().build(
        caffeinate(CacheLoader.asyncReloading(new CacheLoader<Integer, Integer>() {
          @Override public Integer load(Integer key) {
            return -key;
          }
        }, Runnable::run)));
    assertThat(cache.getAll(ImmutableList.of(1, 2, 3)))
        .isEqualTo(Map.of(1, -1, 2, -2, 3, -3));
  }

  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  private static ThreadLocal<?> getNullBulkLoad(LoadingCache<Integer, Integer> cache)
      throws NoSuchFieldException, IllegalAccessException {
    var field = cache.getClass().getDeclaredField("nullBulkLoad");
    field.setAccessible(true);
    return (ThreadLocal<?>) field.get(cache);
  }

  @Test
  void reload_interrupted() {
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().executor(MoreExecutors.directExecutor()),
        new CacheLoader<>() {
          @Override public Integer load(Integer key) throws InterruptedException {
            throw new InterruptedException();
          }
        });
    cache.put(1, 1);

    // Unlike Guava, Caffeine does not leak the exception to the caller
    cache.refresh(1);
  }

  @Test
  void reload_throwable() {
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().executor(MoreExecutors.directExecutor()),
        new CacheLoader<>() {
          @Override public Integer load(Integer key) throws Exception {
            throw new IllegalStateException();
          }
        });
    cache.put(1, 1);

    // Unlike Guava, Caffeine does not leak the exception to the caller
    cache.refresh(1);
  }

  @Test
  void cacheLoader_null() {
    assertThrows(NullPointerException.class, () -> CaffeinatedGuava.caffeinate(nullRef()));

    var caffeine1 = CaffeinatedGuava.caffeinate(CacheLoader.from(key -> nullRef()));
    assertThrows(InvalidCacheLoadException.class, () -> caffeine1.load(1));

    var caffeine2 = CaffeinatedGuava.caffeinate(CacheLoader.from(key -> nullRef()));
    var e1 = assertThrows(CompletionException.class, () ->
        caffeine2.asyncReload(1, 2, Runnable::run).join());
    assertThat(e1).hasCauseThat().isInstanceOf(InvalidCacheLoadException.class);

    var caffeine3 = CaffeinatedGuava.caffeinate(new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public ListenableFuture<Integer> reload(Integer key, Integer oldValue) {
        return nullRef();
      }
    });
    var e2 = assertThrows(CompletionException.class, () ->
        caffeine3.asyncReload(1, 2, Runnable::run).join());
    assertThat(e2).hasCauseThat().isInstanceOf(InvalidCacheLoadException.class);
  }

  @Test
  void cacheLoader_exception() {
    runCacheLoaderExceptionTest(new InterruptedException());
    runCacheLoaderExceptionTest(new RuntimeException());
    runCacheLoaderExceptionTest(new Exception());
  }

  private static void runCacheLoaderExceptionTest(Exception error) {
    var guava = new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) throws Exception {
        throw error;
      }
      @Override public ImmutableMap<Integer, Integer> loadAll(
          Iterable<? extends Integer> keys) throws Exception {
        throw error;
      }
    };
    var caffeine = CaffeinatedGuava.caffeinate(guava);
    var e1 = assertThrows(error.getClass(), () -> caffeine.load(1));
    assertThat(e1).isSameInstanceAs(error);

    var e2 = assertThrows(error.getClass(), () -> caffeine.loadAll(Set.of(1, 2)));
    assertThat(e2).isSameInstanceAs(error);

    var e3 = assertThrows(CompletionException.class, () ->
        caffeine.asyncReload(1, 2, Runnable::run).join());
    assertThat(e3).hasCauseThat().isSameInstanceAs(error);
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void getUnchecked_loaderNullPointerException(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var npe = new NullPointerException("loader bug");
    var cache = factory.apply(new CacheLoader<>() {
      @SuppressWarnings("PMD.AvoidThrowingNullPointerException")
      @Override public Integer load(Integer key) {
        throw npe;
      }
    });
    var e = assertThrows(UncheckedExecutionException.class, () -> cache.getUnchecked(1));
    assertThat(e).hasCauseThat().isSameInstanceAs(npe);
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void getUnchecked_nullKey(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var cache = factory.apply(CacheLoader.from(key -> key));
    assertThrows(NullPointerException.class, () -> cache.getUnchecked(nullRef()));
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void getAll_loaderNullPointerException(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var npe = new NullPointerException("loader bug");
    var cache = factory.apply(new CacheLoader<>() {
      @SuppressWarnings("PMD.AvoidThrowingNullPointerException")
      @Override public Integer load(Integer key) {
        throw npe;
      }
    });
    var e = assertThrows(UncheckedExecutionException.class,
        () -> cache.getAll(ImmutableList.of(1, 2)));
    assertThat(e).hasCauseThat().isSameInstanceAs(npe);
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void getAll_bulkLoaderNullPointerException(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var npe = new NullPointerException("bulk loader bug");
    var cache = factory.apply(new CacheLoader<>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @SuppressWarnings("PMD.AvoidThrowingNullPointerException")
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        throw npe;
      }
    });
    var e = assertThrows(UncheckedExecutionException.class,
        () -> cache.getAll(ImmutableList.of(1, 2)));
    assertThat(e).hasCauseThat().isSameInstanceAs(npe);
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void getAll_nullKeys(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var cache = factory.apply(CacheLoader.from(key -> key));
    assertThrows(NullPointerException.class, () -> cache.getAll(nullRef()));
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void apply_loaderNullPointerException(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var npe = new NullPointerException("loader bug");
    var cache = factory.apply(new CacheLoader<>() {
      @SuppressWarnings("PMD.AvoidThrowingNullPointerException")
      @Override public Integer load(Integer key) {
        throw npe;
      }
    });
    @SuppressWarnings("deprecation")
    var e = assertThrows(UncheckedExecutionException.class, () -> cache.apply(1));
    assertThat(e).hasCauseThat().isSameInstanceAs(npe);
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void apply_loaderCheckedException(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var error = new IOException("checked");
    var cache = factory.apply(new CacheLoader<>() {
      @Override public Integer load(Integer key) throws Exception {
        throw error;
      }
    });
    @SuppressWarnings("deprecation")
    var e = assertThrows(UncheckedExecutionException.class, () -> cache.apply(1));
    // The caffeinate(loader) path adds a CompletionException wrap from Caffeine's mapping
    // function; pure Guava and the Caffeine wrapper with a Guava loader unwrap to the cause.
    assertThat(Throwables.getRootCause(e)).isSameInstanceAs(error);
  }

  @ParameterizedTest
  @SuppressWarnings("deprecation")
  @MethodSource("bulkLoadingCaches")
  void apply_nullKey(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var cache = factory.apply(CacheLoader.from(key -> key));
    assertThrows(NullPointerException.class, () -> cache.apply(nullRef()));
  }

  @Test
  void cacheLoader_single() throws Exception {
    var error = new Exception();
    var guava = new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) throws Exception {
        if (key > 0) {
          return -key;
        }
        throw error;
      }
    };
    var caffeine = CaffeinatedGuava.caffeinate(guava);
    assertThat(caffeine).isNotInstanceOf(ExternalBulkLoader.class);
    checkSingleLoader(error, guava, caffeine);
  }

  @Test
  void cacheLoader_bulk() throws Exception {
    var error = new Exception();
    var guava = new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) throws Exception {
        if (key > 0) {
          return -key;
        }
        throw error;
      }
      @Override public ImmutableMap<Integer, Integer> loadAll(
          Iterable<? extends Integer> keys) throws Exception {
        if (Iterables.all(keys, key -> key > 0)) {
          return Maps.toMap(ImmutableSet.copyOf(keys), key -> -key);
        }
        throw error;
      }
    };
    var caffeine = CaffeinatedGuava.caffeinate(guava);
    checkSingleLoader(error, guava, caffeine);
    checkBulkLoader(error, caffeine);
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void cacheLoader_bulk_nullValue(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var cache = factory.apply(new CacheLoader<>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        var result = new HashMap<Integer, Integer>();
        for (var key : keys) {
          result.put(key, null);
        }
        return result;
      }
    });
    assertThrows(InvalidCacheLoadException.class, () -> cache.getAll(ImmutableList.of(1, 2, 3)));
  }

  @ParameterizedTest
  @MethodSource("bulkLoadingCaches")
  void cacheLoader_bulk_nullMap(
      Function<CacheLoader<Integer, Integer>, LoadingCache<Integer, Integer>> factory) {
    var cache = factory.apply(new CacheLoader<>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        return nullRef();
      }
    });
    assertThrows(InvalidCacheLoadException.class, () -> cache.getAll(ImmutableList.of(1, 2, 3)));
  }

  @Test
  void cacheLoader_reload() throws Exception {
    var reloader = SettableFuture.<Integer>create();
    var caffeine = CaffeinatedGuava.caffeinate(new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public ListenableFuture<Integer> reload(Integer key, Integer oldValue) {
        return reloader;
      }
    });
    var future = caffeine.asyncReload(1, 2, Runnable::run);
    assertFalse(future.isDone());

    reloader.set(3);
    assertTrue(future.isDone());
    assertThat(future.join()).isEqualTo(3);
  }

  @Test
  void cacheLoader_reloadFailure() throws Exception {
    var reloader = SettableFuture.<Integer>create();
    var caffeine = CaffeinatedGuava.caffeinate(new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override public ListenableFuture<Integer> reload(Integer key, Integer oldValue) {
        return reloader;
      }
    });
    var future = caffeine.asyncReload(1, 2, Runnable::run);
    assertFalse(future.isDone());

    var error = new IllegalStateException();
    reloader.setException(error);
    assertTrue(future.isCompletedExceptionally());

    var e = assertThrows(CompletionException.class, future::join);
    assertThat(e).hasCauseThat().isSameInstanceAs(error);
  }

  static Stream<Cache<String, String>> caches() {
    return Stream.of(CacheBuilder.newBuilder().build(),
        CaffeinatedGuava.build(Caffeine.newBuilder()));
  }

  static Stream<Function<CacheLoader<?, ?>, LoadingCache<?, ?>>> bulkLoadingCaches() {
    return Stream.of(
        loader -> CacheBuilder.newBuilder().build(loader),
        loader -> CaffeinatedGuava.build(Caffeine.newBuilder(), loader),
        loader -> CaffeinatedGuava.build(Caffeine.newBuilder(), caffeinate(loader)));
  }

  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  private static void checkSingleLoader(Exception error, CacheLoader<Integer, Integer> guava,
      com.github.benmanes.caffeine.cache.CacheLoader<Integer, Integer> caffeine) throws Exception {
    assertThat(caffeine).isInstanceOf(ExternalSingleLoader.class);
    assertThat(caffeine).isNotInstanceOf(InternalBulkLoader.class);
    assertThat(caffeine).isNotInstanceOf(InternalSingleLoader.class);
    assertThat(((CaffeinatedLoader<?, ?>) caffeine).cacheLoader).isSameInstanceAs(guava);

    assertThat(caffeine.load(1)).isEqualTo(-1);
    var e1 = assertThrows(error.getClass(), () -> caffeine.load(-1));
    assertThat(e1).isSameInstanceAs(error);

    assertThat(caffeine.asyncReload(1, 2, Runnable::run).join()).isEqualTo(-1);
    var e2 = assertThrows(CompletionException.class, () ->
        caffeine.asyncReload(-1, 2, Runnable::run).join());
    assertThat(e2).hasCauseThat().isSameInstanceAs(error);
  }

  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  private static void checkBulkLoader(Exception error,
      com.github.benmanes.caffeine.cache.CacheLoader<Integer, Integer> caffeine) throws Exception {
    assertThat(caffeine).isInstanceOf(ExternalBulkLoader.class);
    assertThat(caffeine.loadAll(Set.of(1, 2, 3))).isEqualTo(Map.of(1, -1, 2, -2, 3, -3));
    var e = assertThrows(error.getClass(), () -> caffeine.loadAll(Set.of(1, -1)));
    assertThat(e).isSameInstanceAs(error);
  }

  /** Returns {@code null} for use when testing null checks while satisfying null analysis tools. */
  @SuppressFBWarnings({"AI_ANNOTATION_ISSUES_NEEDS_NULLABLE", "NP_NONNULL_RETURN_VIOLATION"})
  @SuppressWarnings({"DataFlowIssue", "NullableProblems",
      "NullAway", "TypeParameterUnusedInFormals"})
  private static <T> @NonNull T nullRef() {
    return null;
  }

  enum IdentityLoader implements com.github.benmanes.caffeine.cache.CacheLoader<Object, Object> {
    INSTANCE;

    @CanIgnoreReturnValue
    @Override public Object load(Object key) {
      return key;
    }
  }
}
