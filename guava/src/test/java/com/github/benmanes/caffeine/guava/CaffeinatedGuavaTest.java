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

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

import org.junit.Assert;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaCache.CacheLoaderException;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.BulkLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.ExternalizedBulkLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.ExternalizedSingleLoader;
import com.github.benmanes.caffeine.guava.CaffeinatedGuavaLoadingCache.SingleLoader;
import com.github.benmanes.caffeine.guava.compatibility.TestingCacheLoaders;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.testing.SerializableTester;
import com.google.common.util.concurrent.MoreExecutors;

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

  public void testReflectivelyConstruct() throws Exception {
    Constructor<?> constructor = CaffeinatedGuava.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  public void testHasMethod_notFound() throws Exception {
    assertFalse(CaffeinatedGuava.hasMethod(TestingCacheLoaders.identityLoader(), "abc"));
  }

  public void testReload_interrupted() {
    try {
      LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(
          Caffeine.newBuilder().executor(MoreExecutors.directExecutor()),
          new CacheLoader<Integer, Integer>() {
            @Override public Integer load(Integer key) throws Exception {
              throw new InterruptedException();
            }
          });
      cache.put(1, 1);
      cache.refresh(1);
    } catch (CacheLoaderException e) {
      assertTrue(Thread.currentThread().isInterrupted());
    }
  }

  public void testReload_throwable() {
    try {
      LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(
          Caffeine.newBuilder().executor(MoreExecutors.directExecutor()),
          new CacheLoader<Integer, Integer>() {
            @Override public Integer load(Integer key) throws Exception {
              throw new Exception();
            }
          });
      cache.put(1, 1);
      cache.refresh(1);
    } catch (CacheLoaderException e) {
      assertTrue(Thread.currentThread().isInterrupted());
    }
  }

  public void testCacheLoader_null() throws Exception {
    try {
      CaffeinatedGuava.caffeinate(null);
      Assert.fail();
    } catch (NullPointerException expected) {}

    try {
      var caffeine = CaffeinatedGuava.caffeinate(CacheLoader.from(key -> null));
      caffeine.load(1);
      Assert.fail();
    } catch (InvalidCacheLoadException expected) {}
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
    try {
      caffeine.load(1);
      Assert.fail();
    } catch (Exception e) {
      assertThat(e).isSameInstanceAs(error);
    }
    try {
      caffeine.loadAll(Set.of(1, 2));
      Assert.fail();
    } catch (Exception e) {
      assertThat(e).isSameInstanceAs(error);
    }
    try {
      caffeine.asyncReload(1, 2, Runnable::run).join();
      Assert.fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseThat().isSameInstanceAs(error);
    }
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
    assertThat(caffeine).isNotInstanceOf(BulkLoader.class);
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

  private static void checkSingleLoader(Exception error, CacheLoader<Integer, Integer> guava,
      com.github.benmanes.caffeine.cache.CacheLoader<Integer, Integer> caffeine) throws Exception {
    assertThat(caffeine).isInstanceOf(ExternalizedSingleLoader.class);
    assertThat(((SingleLoader<?, ?>) caffeine).cacheLoader).isSameInstanceAs(guava);

    assertThat(caffeine.load(1)).isEqualTo(-1);
    try {
      caffeine.load(-1);
      Assert.fail();
    } catch (Exception e) {
      assertThat(e).isSameInstanceAs(error);
    }

    assertThat(caffeine.asyncReload(1, 2, Runnable::run).join()).isEqualTo(-1);
    try {
      caffeine.asyncReload(-1, 2, Runnable::run).join();
      Assert.fail();
    } catch (CompletionException e) {
      assertThat(e).hasCauseThat().isSameInstanceAs(error);
    }
  }

  private static void checkBulkLoader(Exception error,
      com.github.benmanes.caffeine.cache.CacheLoader<Integer, Integer> caffeine) throws Exception {
    assertThat(caffeine).isInstanceOf(ExternalizedBulkLoader.class);
    assertThat(caffeine.loadAll(Set.of(1, 2, 3))).isEqualTo(Map.of(1, -1, 2, -2, 3, -3));
    try {
      caffeine.loadAll(Set.of(1, -1));
      Assert.fail();
    } catch (Exception e) {
      assertThat(e).isSameInstanceAs(error);
    }
  }

  enum IdentityLoader implements com.github.benmanes.caffeine.cache.CacheLoader<Object, Object> {
    INSTANCE;

    @Override
    public Object load(Object key) {
      return key;
    }
  }
}
