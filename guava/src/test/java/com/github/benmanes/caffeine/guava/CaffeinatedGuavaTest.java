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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.CaffeinatedLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.ExternalBulkLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.ExternalSingleLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.InternalBulkLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.InternalSingleLoader;
import com.github.benmanes.caffeine.guava.compatibility.TestingCacheLoaders;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.testing.SerializableTester;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

import junit.framework.TestCase;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeinatedGuavaTest extends TestCase {

  public void testSerializable() {
    SerializableTester.reserialize(CaffeinatedGuava.build(Caffeine.newBuilder()));
    SerializableTester.reserialize(CaffeinatedGuava.build(
        Caffeine.newBuilder(), IdentityLoader.INSTANCE));
    SerializableTester.reserialize(CaffeinatedGuava.build(
        Caffeine.newBuilder(), TestingCacheLoaders.identityLoader()));
  }

  public void testReflectivelyConstruct() throws ReflectiveOperationException {
    Constructor<?> constructor = CaffeinatedGuava.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  public void testHasMethod_notFound() {
    assertFalse(CaffeinatedGuava.hasMethod(TestingCacheLoaders.identityLoader(), "abc"));
  }

  public void testReload_interrupted() {
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().executor(MoreExecutors.directExecutor()),
        new CacheLoader<Integer, Integer>() {
          @Override public Integer load(Integer key) throws InterruptedException {
            throw new InterruptedException();
          }
        });
    cache.put(1, 1);

    // Unlike Guava, Caffeine does not leak the exception to the caller
    cache.refresh(1);
  }

  public void testReload_throwable() {
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().executor(MoreExecutors.directExecutor()),
        new CacheLoader<Integer, Integer>() {
          @Override public Integer load(Integer key) throws Exception {
            throw new Exception();
          }
        });
    cache.put(1, 1);

    // Unlike Guava, Caffeine does not leak the exception to the caller
    cache.refresh(1);
  }

  public void testCacheLoader_null() throws Exception {
    assertThrows(NullPointerException.class, () -> CaffeinatedGuava.caffeinate(null));

    var caffeine1 = CaffeinatedGuava.caffeinate(CacheLoader.from(key -> null));
    assertThrows(InvalidCacheLoadException.class, () -> caffeine1.load(1));

    var caffeine2 = CaffeinatedGuava.caffeinate(CacheLoader.from(key -> null));
    var e1 = assertThrows(CompletionException.class, () ->
        caffeine2.asyncReload(1, 2, Runnable::run).join());
    assertThat(e1).hasCauseThat().isInstanceOf(InvalidCacheLoadException.class);

    var caffeine3 = CaffeinatedGuava.caffeinate(new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override
      public ListenableFuture<Integer> reload(Integer key, Integer oldValue) {
        return null;
      }
    });
    var e2 = assertThrows(CompletionException.class, () ->
        caffeine3.asyncReload(1, 2, Runnable::run).join());
    assertThat(e2).hasCauseThat().isInstanceOf(InvalidCacheLoadException.class);
  }

  public void testCacheLoader_exception() throws Exception {
    runCacheLoaderExceptionTest(new InterruptedException());
    runCacheLoaderExceptionTest(new RuntimeException());
    runCacheLoaderExceptionTest(new Exception());
  }

  public void runCacheLoaderExceptionTest(Exception error) throws Exception {
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

  public void testCacheLoader_single() throws Exception {
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

  public void testCacheLoader_bulk() throws Exception {
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


  public void testCacheLoader_reload() throws Exception {
    SettableFuture<Integer> reloader = SettableFuture.create();
    var caffeine = CaffeinatedGuava.caffeinate(new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override
      public ListenableFuture<Integer> reload(Integer key, Integer oldValue) {
        return reloader;
      }
    });
    var future = caffeine.asyncReload(1, 2, Runnable::run);
    assertFalse(future.isDone());

    reloader.set(3);
    assertTrue(future.isDone());
    assertThat(future.join()).isEqualTo(3);
  }

  public void testCacheLoader_reloadFailure() throws Exception {
    SettableFuture<Integer> reloader = SettableFuture.create();
    var caffeine = CaffeinatedGuava.caffeinate(new CacheLoader<Integer, Integer>() {
      @Override public Integer load(Integer key) {
        throw new UnsupportedOperationException();
      }
      @Override
      public ListenableFuture<Integer> reload(Integer key, Integer oldValue) {
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

  private static void checkBulkLoader(Exception error,
      com.github.benmanes.caffeine.cache.CacheLoader<Integer, Integer> caffeine) throws Exception {
    assertThat(caffeine).isInstanceOf(ExternalBulkLoader.class);
    assertThat(caffeine.loadAll(Set.of(1, 2, 3))).isEqualTo(Map.of(1, -1, 2, -2, 3, -3));
    var e = assertThrows(error.getClass(), () -> caffeine.loadAll(Set.of(1, -1)));
    assertThat(e).isSameInstanceAs(error);
  }

  enum IdentityLoader implements com.github.benmanes.caffeine.cache.CacheLoader<Object, Object> {
    INSTANCE;

    @CanIgnoreReturnValue
    @Override public Object load(Object key) {
      return key;
    }
  }
}
