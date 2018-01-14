/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.cache;

import static com.google.common.cache.TestingCacheLoaders.bulkLoader;
import static com.google.common.cache.TestingCacheLoaders.constantLoader;
import static com.google.common.cache.TestingCacheLoaders.errorLoader;
import static com.google.common.cache.TestingCacheLoaders.exceptionLoader;
import static com.google.common.cache.TestingCacheLoaders.identityLoader;
import static com.google.common.cache.TestingRemovalListeners.countingRemovalListener;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuava;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.TestingCacheLoaders.IdentityLoader;
import com.google.common.cache.TestingRemovalListeners.CountingRemovalListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.testing.FakeTicker;
import com.google.common.testing.TestLogHandler;
import com.google.common.util.concurrent.Callables;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.common.util.concurrent.Uninterruptibles;

import junit.framework.TestCase;

/**
 * Tests relating to cache loading: concurrent loading, exceptions during loading, etc.
 *
 * @author mike nonemacher
 */
public class CacheLoadingTest extends TestCase {
  Logger logger = Logger.getLogger("com.github.benmanes.caffeine.cache.BoundedLocalCache");
  TestLogHandler logHandler;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    logHandler = new TestLogHandler();
    logger.addHandler(logHandler);
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    // TODO(cpovirk): run tests in other thread instead of messing with main thread interrupt status
    Thread.interrupted();
    logger.removeHandler(logHandler);
  }

  private void checkNothingLogged() {
    assertTrue(logHandler.getStoredLogRecords().isEmpty());
  }

  private void checkLoggedCause(Throwable t) {
    //assertSame(t, popLoggedThrowable().getCause());
  }

  private void checkLoggedInvalidLoad() {
    //assertTrue(popLoggedThrowable() instanceof InvalidCacheLoadException);
  }

  public void testLoad() throws ExecutionException {
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats().executor(MoreExecutors.directExecutor()), identityLoader());
    //LoadingCache<Object, Object> cache = CacheBuilder.newBuilder().recordStats().build(identityLoader());
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    Object key = new Object();
    assertSame(key, cache.get(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    key = new Object();
    assertSame(key, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    key = new Object();
    cache.refresh(key);
    checkNothingLogged();
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(3, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(key, cache.get(key));
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(3, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    Object value = new Object();
    // callable is not called
    assertSame(key, cache.get(key, throwing(new Exception())));
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(3, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(2, stats.hitCount());

    key = new Object();
    assertSame(value, cache.get(key, Callables.returning(value)));
    stats = cache.stats();
    assertEquals(3, stats.missCount());
    assertEquals(4, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(2, stats.hitCount());
  }

  public void testReload() throws ExecutionException {
    final Object one = new Object();
    final Object two = new Object();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFuture(two);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkNothingLogged();
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(two, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testRefresh() {
    final Object one = new Object();
    final Object two = new Object();
    FakeTicker ticker = new FakeTicker();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFuture(two);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats()
        .ticker(ticker::read)
        .refreshAfterWrite(1, MILLISECONDS)
        .executor(MoreExecutors.directExecutor()),
        loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    cache.getUnchecked(key); // Allow refresh to return old value while refreshing
    assertSame(two, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(3, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(two, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(4, stats.hitCount());
  }

  public void testRefresh_getIfPresent() {
    final Object one = new Object();
    final Object two = new Object();
    FakeTicker ticker = new FakeTicker();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFuture(two);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats()
        .ticker(ticker::read)
        .refreshAfterWrite(1, MILLISECONDS)
        .executor(MoreExecutors.directExecutor()),
        loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getIfPresent(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getIfPresent(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(2, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(two, cache.getIfPresent(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(3, stats.hitCount());
  }

  public void testBulkLoad_default() throws ExecutionException {
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats(), TestingCacheLoaders.<Integer>identityLoader());
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertEquals(ImmutableMap.of(), cache.getAll(ImmutableList.<Integer>of()));
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertEquals(ImmutableMap.of(1, 1), cache.getAll(asList(1)));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertEquals(ImmutableMap.of(1, 1, 2, 2, 3, 3, 4, 4), cache.getAll(asList(1, 2, 3, 4)));
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(4, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    assertEquals(ImmutableMap.of(2, 2, 3, 3), cache.getAll(asList(2, 3)));
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(4, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(3, stats.hitCount());

    // duplicate keys are ignored, and don't impact stats
    assertEquals(ImmutableMap.of(4, 4, 5, 5), cache.getAll(asList(4, 5)));
    stats = cache.stats();
    assertEquals(5, stats.missCount());
    assertEquals(5, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(4, stats.hitCount());
  }

  public void testBulkLoad_loadAll() throws ExecutionException {
    IdentityLoader<Integer> backingLoader = identityLoader();
    LoadingCache<Integer, Integer> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats().executor(MoreExecutors.directExecutor()), bulkLoader(backingLoader));
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertEquals(ImmutableMap.of(), cache.getAll(ImmutableList.<Integer>of()));
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertEquals(ImmutableMap.of(1, 1), cache.getAll(asList(1)));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertEquals(ImmutableMap.of(1, 1, 2, 2, 3, 3, 4, 4), cache.getAll(asList(1, 2, 3, 4)));
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    assertEquals(ImmutableMap.of(2, 2, 3, 3), cache.getAll(asList(2, 3)));
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(2, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(3, stats.hitCount());

    // duplicate keys are ignored, and don't impact stats
    assertEquals(ImmutableMap.of(4, 4, 5, 5), cache.getAll(asList(4, 5)));
    stats = cache.stats();
    assertEquals(5, stats.missCount());
    assertEquals(3, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(4, stats.hitCount());
  }

  public void testBulkLoad_extra() throws ExecutionException {
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return new Object();
      }

      @Override
      public Map<Object, Object> loadAll(Iterable<? extends Object> keys) {
        Map<Object, Object> result = Maps.newHashMap();
        for (Object key : keys) {
          Object value = new Object();
          result.put(key, value);
          // add extra entries
          result.put(value, key);
        }
        return result;
      }
    };
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().executor(MoreExecutors.directExecutor()), loader);

    Object[] lookupKeys = new Object[] { new Object(), new Object(), new Object() };
    Map<Object, Object> result = cache.getAll(asList(lookupKeys));
    assertThat(result.keySet()).containsExactlyElementsIn(asList(lookupKeys));
    for (Map.Entry<Object, Object> entry : result.entrySet()) {
      Object key = entry.getKey();
      Object value = entry.getValue();
      assertSame(value, result.get(key));
      assertNull(result.get(value));
      assertSame(value, cache.asMap().get(key));
      assertSame(key, cache.asMap().get(value));
    }
  }

  public void testBulkLoad_clobber() throws ExecutionException {
    final Object extraKey = new Object();
    final Object extraValue = new Object();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        throw new AssertionError();
      }

      @Override
      public Map<Object, Object> loadAll(Iterable<? extends Object> keys) {
        Map<Object, Object> result = Maps.newHashMap();
        for (Object key : keys) {
          Object value = new Object();
          result.put(key, value);
        }
        result.put(extraKey, extraValue);
        return result;
      }
    };
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder(), loader);
    cache.asMap().put(extraKey, extraKey);
    assertSame(extraKey, cache.asMap().get(extraKey));

    Object[] lookupKeys = new Object[] { new Object(), new Object(), new Object() };
    Map<Object, Object> result = cache.getAll(asList(lookupKeys));
    assertThat(result.keySet()).containsExactlyElementsIn(asList(lookupKeys));
    for (Map.Entry<Object, Object> entry : result.entrySet()) {
      Object key = entry.getKey();
      Object value = entry.getValue();
      assertSame(value, result.get(key));
      assertSame(value, cache.asMap().get(key));
    }
    assertNull(result.get(extraKey));
    assertSame(extraValue, cache.asMap().get(extraKey));
  }

  public void testBulkLoad_clobberNullValue() throws ExecutionException {
    final Object extraKey = new Object();
    final Object extraValue = new Object();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        throw new AssertionError();
      }

      @Override
      public Map<Object, Object> loadAll(Iterable<? extends Object> keys) {
        Map<Object, Object> result = Maps.newHashMap();
        for (Object key : keys) {
          Object value = new Object();
          result.put(key, value);
        }
        result.put(extraKey, extraValue);
        result.put(extraValue, null);
        return result;
      }
    };
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder(), loader);
    cache.asMap().put(extraKey, extraKey);
    assertSame(extraKey, cache.asMap().get(extraKey));

    Object[] lookupKeys = new Object[] { new Object(), new Object(), new Object() };
    try {
      cache.getAll(asList(lookupKeys));
      fail();
    } catch (InvalidCacheLoadException expected) {}

    for (Object key : lookupKeys) {
      assertTrue(cache.asMap().containsKey(key));
    }
    assertSame(extraValue, cache.asMap().get(extraKey));
    assertFalse(cache.asMap().containsKey(extraValue));
  }

  public void testBulkLoad_clobberNullKey() throws ExecutionException {
    final Object extraKey = new Object();
    final Object extraValue = new Object();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        throw new AssertionError();
      }

      @Override
      public Map<Object, Object> loadAll(Iterable<? extends Object> keys) {
        Map<Object, Object> result = Maps.newHashMap();
        for (Object key : keys) {
          Object value = new Object();
          result.put(key, value);
        }
        result.put(extraKey, extraValue);
        result.put(null, extraKey);
        return result;
      }
    };
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder(), loader);
    cache.asMap().put(extraKey, extraKey);
    assertSame(extraKey, cache.asMap().get(extraKey));

    Object[] lookupKeys = new Object[] { new Object(), new Object(), new Object() };
    try {
      cache.getAll(asList(lookupKeys));
      fail();
    } catch (InvalidCacheLoadException expected) {}

    for (Object key : lookupKeys) {
      assertTrue(cache.asMap().containsKey(key));
    }
    assertSame(extraValue, cache.asMap().get(extraKey));
    assertFalse(cache.asMap().containsValue(extraKey));
  }

  public void testBulkLoad_partial() throws ExecutionException {
    final Object extraKey = new Object();
    final Object extraValue = new Object();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        throw new AssertionError();
      }

      @Override
      public Map<Object, Object> loadAll(Iterable<? extends Object> keys) {
        Map<Object, Object> result = Maps.newHashMap();
        // ignore request keys
        result.put(extraKey, extraValue);
        return result;
      }
    };
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder(), loader);

    Object[] lookupKeys = new Object[] { new Object(), new Object(), new Object() };
    try {
      cache.getAll(asList(lookupKeys));
      fail();
    } catch (InvalidCacheLoadException expected) {}
    assertSame(extraValue, cache.asMap().get(extraKey));
  }

  public void testLoadNull() throws ExecutionException {
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .recordStats(), constantLoader(null));
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.get(new Object());
      fail();
    } catch (InvalidCacheLoadException expected) {}
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getUnchecked(new Object());
      fail();
    } catch (InvalidCacheLoadException expected) {}
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(new Object());
    checkLoggedInvalidLoad();
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(3, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.get(new Object(), Callables.returning(null));
      fail();
    } catch (InvalidCacheLoadException expected) {}
    stats = cache.stats();
    assertEquals(3, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(4, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (InvalidCacheLoadException expected) {}
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(5, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testReloadNull() throws ExecutionException {
    final Object one = new Object();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return null;
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkLoggedInvalidLoad();
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testReloadNullFuture() throws ExecutionException {
    final Object one = new Object();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFuture(null);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkLoggedInvalidLoad();
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testRefreshNull() {
    final Object one = new Object();
    FakeTicker ticker = new FakeTicker();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFuture(null);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats()
        .ticker(ticker::read)
        .refreshAfterWrite(1, MILLISECONDS)
        .executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    // refreshed
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(2, stats.hitCount());

    ticker.advance(2, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(3, stats.hitCount());
  }

  public void testBulkLoadNull() throws ExecutionException {
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats().executor(MoreExecutors.directExecutor()), bulkLoader(constantLoader(null)));
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (InvalidCacheLoadException expected) {}
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testBulkLoadNullMap() throws ExecutionException {
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats(), new CacheLoader<Object, Object>() {
          @Override
          public Object load(Object key) {
            throw new AssertionError();
          }

          @Override
          public Map<Object, Object> loadAll(Iterable<? extends Object> keys) {
            return null;
          }
        });

    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (InvalidCacheLoadException expected) {}
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testLoadError() throws ExecutionException {
    Error e = new Error();
    CacheLoader<Object, Object> loader = errorLoader(e);
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats().executor(MoreExecutors.directExecutor()), loader);
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.get(new Object());
      fail();
    } catch (ExecutionError expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getUnchecked(new Object());
      fail();
    } catch (ExecutionError expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(new Object());
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(3, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    final Error callableError = new Error();
    try {
      cache.get(new Object(), new Callable<Object>() {
        @Override
        public Object call() {
          throw callableError;
        }
      });
      fail();
    } catch (ExecutionError expected) {
      assertSame(callableError, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(3, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(4, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (ExecutionError expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(5, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testReloadError() throws ExecutionException {
    final Object one = new Object();
    final Error e = new Error();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        throw e;
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testReloadFutureError() throws ExecutionException {
    final Object one = new Object();
    final Error e = new Error();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFailedFuture(e);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testRefreshError() {
    final Object one = new Object();
    final Error e = new Error();
    FakeTicker ticker = new FakeTicker();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFailedFuture(e);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats()
        .ticker(ticker::read)
        .refreshAfterWrite(1, MILLISECONDS)
        .executor(MoreExecutors.directExecutor()),
        loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    // refreshed
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(2, stats.hitCount());

    ticker.advance(2, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(3, stats.hitCount());
  }

  public void testBulkLoadError() throws ExecutionException {
    Error e = new Error();
    CacheLoader<Object, Object> loader = errorLoader(e);
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats(), bulkLoader(loader));
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (ExecutionError expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testLoadCheckedException() {
    Exception e = new Exception();
    CacheLoader<Object, Object> loader = exceptionLoader(e);
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.get(new Object());
      fail();
    } catch (ExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getUnchecked(new Object());
      fail();
    } catch (UncheckedExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(new Object());
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(3, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    Exception callableException = new Exception();
    try {
      cache.get(new Object(), throwing(callableException));
      fail();
    } catch (ExecutionException expected) {
      assertSame(callableException, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(3, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(4, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (ExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(5, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testLoadInterruptedException() {
    Exception e = new InterruptedException();
    CacheLoader<Object, Object> loader = exceptionLoader(e);
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    // Sanity check:
    assertFalse(Thread.interrupted());

    try {
      cache.get(new Object());
      fail();
    } catch (ExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    assertTrue(Thread.interrupted());
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getUnchecked(new Object());
      fail();
    } catch (UncheckedExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    assertTrue(Thread.interrupted());
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(new Object());
    assertTrue(Thread.interrupted());
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(3, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    Exception callableException = new InterruptedException();
    try {
      cache.get(new Object(), throwing(callableException));
      fail();
    } catch (ExecutionException expected) {
      assertSame(callableException, expected.getCause());
    }
    assertTrue(Thread.interrupted());
    stats = cache.stats();
    assertEquals(3, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(4, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (ExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    assertTrue(Thread.interrupted());
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(5, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testReloadCheckedException() {
    final Object one = new Object();
    final RuntimeException e = new RuntimeException();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        throw e;
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testReloadFutureCheckedException() {
    final Object one = new Object();
    final Exception e = new Exception();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFailedFuture(e);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testRefreshCheckedException() {
    final Object one = new Object();
    final Exception e = new Exception();
    FakeTicker ticker = new FakeTicker();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFailedFuture(e);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats()
        .ticker(ticker::read)
        .refreshAfterWrite(1, MILLISECONDS)
        .executor(MoreExecutors.directExecutor()),
        loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    // refreshed
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(2, stats.hitCount());

    ticker.advance(2, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(3, stats.hitCount());
  }

  public void testBulkLoadCheckedException() {
    Exception e = new Exception();
    CacheLoader<Object, Object> loader = exceptionLoader(e);
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats(), bulkLoader(loader));
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (ExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testBulkLoadInterruptedException() {
    Exception e = new InterruptedException();
    CacheLoader<Object, Object> loader = exceptionLoader(e);
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats().executor(MoreExecutors.directExecutor()), bulkLoader(loader));
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (ExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    assertTrue(Thread.interrupted());
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testLoadUncheckedException() throws ExecutionException {
    Exception e = new RuntimeException();
    CacheLoader<Object, Object> loader = exceptionLoader(e);
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.get(new Object());
      fail();
    } catch (UncheckedExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getUnchecked(new Object());
      fail();
    } catch (UncheckedExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(new Object());
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(2, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(3, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    Exception callableException = new RuntimeException();
    try {
      cache.get(new Object(), throwing(callableException));
      fail();
    } catch (UncheckedExecutionException expected) {
      assertSame(callableException, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(3, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(4, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (UncheckedExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(4, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(5, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testReloadUncheckedException() throws ExecutionException {
    final Object one = new Object();
    final RuntimeException e = new RuntimeException();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        throw e;
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testReloadFutureUncheckedException() throws ExecutionException {
    final Object one = new Object();
    final Exception e = new RuntimeException();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFailedFuture(e);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder().recordStats().executor(MoreExecutors.directExecutor()), loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    cache.refresh(key);
    checkLoggedCause(e);
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());
  }

  public void testRefreshUncheckedException() {
    final Object one = new Object();
    final Exception e = new RuntimeException();
    FakeTicker ticker = new FakeTicker();
    CacheLoader<Object, Object> loader = new CacheLoader<Object, Object>() {
      @Override
      public Object load(Object key) {
        return one;
      }

      @Override
      public ListenableFuture<Object> reload(Object key, Object oldValue) {
        return Futures.immediateFailedFuture(e);
      }
    };

    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats()
        .ticker(ticker::read)
        .refreshAfterWrite(1, MILLISECONDS)
        .executor(MoreExecutors.directExecutor()),
        loader);
    Object key = new Object();
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(1, stats.hitCount());

    ticker.advance(1, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    // refreshed
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(2, stats.hitCount());

    ticker.advance(2, MILLISECONDS);
    assertSame(one, cache.getUnchecked(key));
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(1, stats.loadSuccessCount());
    assertEquals(2, stats.loadExceptionCount());
    assertEquals(3, stats.hitCount());
  }

  public void testBulkLoadUncheckedException() throws ExecutionException {
    Exception e = new RuntimeException();
    CacheLoader<Object, Object> loader = exceptionLoader(e);
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .recordStats(), bulkLoader(loader));
    CacheStats stats = cache.stats();
    assertEquals(0, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(0, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());

    try {
      cache.getAll(asList(new Object()));
      fail();
    } catch (UncheckedExecutionException expected) {
      assertSame(e, expected.getCause());
    }
    stats = cache.stats();
    assertEquals(1, stats.missCount());
    assertEquals(0, stats.loadSuccessCount());
    assertEquals(1, stats.loadExceptionCount());
    assertEquals(0, stats.hitCount());
  }

  public void testReloadAfterFailure() throws ExecutionException {
    final AtomicInteger count = new AtomicInteger();
    final RuntimeException e =
        new IllegalStateException("exception to trigger failure on first load()");
    CacheLoader<Integer, String> failOnceFunction = new CacheLoader<Integer, String>() {

      @Override
      public String load(Integer key) {
        if (count.getAndIncrement() == 0) {
          throw e;
        }
        return key.toString();
      }
    };
    CountingRemovalListener<Integer, String> removalListener = countingRemovalListener();
    LoadingCache<Integer, String> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .removalListener(removalListener).executor(MoreExecutors.directExecutor()),
        failOnceFunction);

    try {
      cache.getUnchecked(1);
      fail();
    } catch (UncheckedExecutionException ue) {
      assertSame(e, ue.getCause());
    }

    assertEquals("1", cache.getUnchecked(1));
    assertEquals(0, removalListener.getCount());

    count.set(0);
    cache.refresh(2);
    checkLoggedCause(e);

    assertEquals("2", cache.getUnchecked(2));
    assertEquals(0, removalListener.getCount());

  }

  /**
   * Make sure LoadingCache correctly wraps ExecutionExceptions and UncheckedExecutionExceptions.
   */
  public void testLoadingExceptionWithCause() throws ExecutionException {
    final Exception cause = new Exception();
    final UncheckedExecutionException uee = new UncheckedExecutionException(cause);
    final ExecutionException ee = new ExecutionException(cause);

    LoadingCache<Object, Object> cacheUnchecked =
        CaffeinatedGuava.build(Caffeine.newBuilder(), exceptionLoader(uee));
    LoadingCache<Object, Object> cacheChecked =
        CaffeinatedGuava.build(Caffeine.newBuilder(), exceptionLoader(ee));

    try {
      cacheUnchecked.get(new Object());
      fail();
    } catch (UncheckedExecutionException caughtEe) {
      assertSame(uee, caughtEe.getCause());
    }

    try {
      cacheUnchecked.getUnchecked(new Object());
      fail();
    } catch (UncheckedExecutionException caughtUee) {
      assertSame(uee, caughtUee.getCause());
    }

    cacheUnchecked.refresh(new Object());
    checkLoggedCause(uee);

    try {
      cacheUnchecked.getAll(asList(new Object()));
      fail();
    } catch (UncheckedExecutionException caughtEe) {
      assertSame(uee, caughtEe.getCause());
    }

    try {
      cacheChecked.get(new Object());
      fail();
    } catch (ExecutionException caughtEe) {
      assertSame(ee, caughtEe.getCause());
    }

    try {
      cacheChecked.getUnchecked(new Object());
      fail();
    } catch (UncheckedExecutionException caughtUee) {
      assertSame(ee, caughtUee.getCause());
    }

    cacheChecked.refresh(new Object());
    checkLoggedCause(ee);

    try {
      cacheChecked.getAll(asList(new Object()));
      fail();
    } catch (ExecutionException caughtEe) {
      assertSame(ee, caughtEe.getCause());
    }
  }

  public void testBulkLoadingExceptionWithCause() throws ExecutionException {
    final Exception cause = new Exception();
    final UncheckedExecutionException uee = new UncheckedExecutionException(cause);
    final ExecutionException ee = new ExecutionException(cause);

    LoadingCache<Object, Object> cacheUnchecked =
        CaffeinatedGuava.build(Caffeine.newBuilder(), bulkLoader(exceptionLoader(uee)));
    LoadingCache<Object, Object> cacheChecked =
        CaffeinatedGuava.build(Caffeine.newBuilder(), bulkLoader(exceptionLoader(ee)));

    try {
      cacheUnchecked.getAll(asList(new Object()));
      fail();
    } catch (UncheckedExecutionException caughtEe) {
      assertSame(uee, caughtEe.getCause());
    }

    try {
      cacheChecked.getAll(asList(new Object()));
      fail();
    } catch (ExecutionException caughtEe) {
      assertSame(ee, caughtEe.getCause());
    }
  }

  public void testConcurrentLoading() throws InterruptedException {
    testConcurrentLoading(Caffeine.newBuilder());
  }

  public void testConcurrentExpirationLoading() throws InterruptedException {
    testConcurrentLoading(Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.SECONDS)
        .executor(MoreExecutors.directExecutor()));
  }

  private static void testConcurrentLoading(Caffeine<Object, Object> builder)
      throws InterruptedException {
    testConcurrentLoadingDefault(builder);
    testConcurrentLoadingNull(builder);
    testConcurrentLoadingUncheckedException(builder);
    testConcurrentLoadingCheckedException(builder);
  }

  /**
   * On a successful concurrent computation, only one thread does the work, but all the threads get
   * the same result.
   */
  private static void testConcurrentLoadingDefault(Caffeine<Object, Object> builder)
      throws InterruptedException {

    int count = 10;
    final AtomicInteger callCount = new AtomicInteger();
    final CountDownLatch startSignal = new CountDownLatch(count + 1);
    final Object result = new Object();

    LoadingCache<String, Object> cache = CaffeinatedGuava.build(builder,
        new CacheLoader<String, Object>() {
          @Override public Object load(String key) {
            callCount.incrementAndGet();
            assertTrue(Uninterruptibles.awaitUninterruptibly(startSignal, 300, TimeUnit.SECONDS));
            return result;
          }
        });

    List<Object> resultArray = doConcurrentGet(cache, "bar", count, startSignal);

    assertEquals(1, callCount.get());
    for (int i = 0; i < count; i++) {
      assertSame("result(" + i + ") didn't match expected", result, resultArray.get(i));
    }
  }

  /**
   * On a concurrent computation that returns null, all threads should get an
   * InvalidCacheLoadException, with the loader only called once. The result should not be cached
   * (a later request should call the loader again).
   */
  private static void testConcurrentLoadingNull(Caffeine<Object, Object> builder)
      throws InterruptedException {

    int count = 10;
    final AtomicInteger callCount = new AtomicInteger();
    final CountDownLatch startSignal = new CountDownLatch(count + 1);

    LoadingCache<String, String> cache = CaffeinatedGuava.build(builder,
        new CacheLoader<String, String>() {
          @Override public String load(String key) {
            callCount.incrementAndGet();
            assertTrue(Uninterruptibles.awaitUninterruptibly(startSignal, 300, TimeUnit.SECONDS));
            return null;
          }
        });

    List<Object> result = doConcurrentGet(cache, "bar", count, startSignal);

    assertEquals(count, callCount.get());
    for (int i = 0; i < count; i++) {
      assertTrue(result.get(i) instanceof InvalidCacheLoadException);
    }

    // subsequent calls should call the loader again, not get the old exception
    try {
      cache.getUnchecked("bar");
      fail();
    } catch (InvalidCacheLoadException expected) {
    }
    assertEquals(count + 1, callCount.get());
  }

  /**
   * On a concurrent computation that throws an unchecked exception, all threads should get the
   * (wrapped) exception, with the loader called only once. The result should not be cached (a later
   * request should call the loader again).
   */
  private static void testConcurrentLoadingUncheckedException(
      Caffeine<Object, Object> builder) throws InterruptedException {

    int count = 10;
    final AtomicInteger callCount = new AtomicInteger();
    final CountDownLatch startSignal = new CountDownLatch(count + 1);
    final RuntimeException e = new RuntimeException();

    LoadingCache<String, String> cache = CaffeinatedGuava.build(builder,
        new CacheLoader<String, String>() {
          @Override public String load(String key) {
            callCount.incrementAndGet();
            assertTrue(Uninterruptibles.awaitUninterruptibly(startSignal, 300, TimeUnit.SECONDS));
            throw e;
          }
        });

    List<Object> result = doConcurrentGet(cache, "bar", count, startSignal);

    assertEquals(count, callCount.get());
    for (int i = 0; i < count; i++) {
      // doConcurrentGet alternates between calling getUnchecked and calling get, but an unchecked
      // exception thrown by the loader is always wrapped as an UncheckedExecutionException.
      assertTrue(result.get(i) instanceof UncheckedExecutionException);
      assertSame(e, ((UncheckedExecutionException) result.get(i)).getCause());
    }

    // subsequent calls should call the loader again, not get the old exception
    try {
      cache.getUnchecked("bar");
      fail();
    } catch (UncheckedExecutionException expected) {
    }
    assertEquals(count + 1, callCount.get());
  }

  /**
   * On a concurrent computation that throws a checked exception, all threads should get the
   * (wrapped) exception, with the loader called only once. The result should not be cached (a later
   * request should call the loader again).
   */
  private static void testConcurrentLoadingCheckedException(
      Caffeine<Object, Object> builder) throws InterruptedException {

    int count = 10;
    final AtomicInteger callCount = new AtomicInteger();
    final CountDownLatch startSignal = new CountDownLatch(count + 1);
    final Exception e = new Exception();

    LoadingCache<String, String> cache = CaffeinatedGuava.build(builder,
        new CacheLoader<String, String>() {
          @Override public String load(String key) throws Exception {
            callCount.incrementAndGet();
            assertTrue(Uninterruptibles.awaitUninterruptibly(startSignal, 300, TimeUnit.SECONDS));
            throw e;
          }
        });

    List<Object> result = doConcurrentGet(cache, "bar", count, startSignal);

    assertEquals(count, callCount.get());
    for (int i = 0; i < count; i++) {
      // doConcurrentGet alternates between calling getUnchecked and calling get. If we call get(),
      // we should get an ExecutionException; if we call getUnchecked(), we should get an
      // UncheckedExecutionException.
      int mod = i % 2;
      if (mod == 0) {
        assertTrue(result.get(i) instanceof ExecutionException);
        assertSame(e, ((ExecutionException) result.get(i)).getCause());
      } else {
        assertTrue(result.get(i) instanceof UncheckedExecutionException);
        assertSame(e, ((UncheckedExecutionException) result.get(i)).getCause());
      }
    }

    // subsequent calls should call the loader again, not get the old exception
    try {
      cache.getUnchecked("bar");
      fail();
    } catch (UncheckedExecutionException expected) {
    }
    assertEquals(count + 1, callCount.get());
  }

  /**
   * Test-helper method that performs {@code nThreads} concurrent calls to {@code cache.get(key)}
   * or {@code cache.getUnchecked(key)}, and returns a List containing each of the results. The
   * result for any given call to {@code cache.get} or {@code cache.getUnchecked} is the value
   * returned, or the exception thrown.
   *
   * <p>As we iterate from {@code 0} to {@code nThreads}, threads with an even index will call
   * {@code getUnchecked}, and threads with an odd index will call {@code get}. If the cache throws
   * exceptions, this difference may be visible in the returned List.
   */
  private static <K> List<Object> doConcurrentGet(final LoadingCache<K, ?> cache, final K key,
      int nThreads, final CountDownLatch gettersStartedSignal) throws InterruptedException {

    final AtomicReferenceArray<Object> result = new AtomicReferenceArray<Object>(nThreads);
    final CountDownLatch gettersComplete = new CountDownLatch(nThreads);
    AtomicBoolean ready = new AtomicBoolean();
    for (int i = 0; i < nThreads; i++) {
      final int index = i;
      Thread thread = new Thread(new Runnable() {
        @Override public void run() {
          gettersStartedSignal.countDown();
          Object value = null;
          try {
            int mod = index % 2;
            ready.set(true);
            if (mod == 0) {
              value = cache.get(key);
            } else if (mod == 1) {
              value = cache.getUnchecked(key);
            }
            result.set(index, value);
          } catch (Throwable t) {
            result.set(index, t);
          }
          gettersComplete.countDown();
        }
      });
      thread.start();
      // we want to wait until each thread is WAITING - one thread waiting inside CacheLoader.load
      // (in startSignal.await()), and the others waiting for that thread's result.
      while (thread.isAlive() && !ready.get()) {
        Thread.yield();
      }
    }
    gettersStartedSignal.countDown();
    assertTrue(gettersComplete.await(300, TimeUnit.SECONDS));

    List<Object> resultList = Lists.newArrayListWithExpectedSize(nThreads);
    for (int i = 0; i < nThreads; i++) {
      resultList.add(result.get(i));
    }
    return resultList;
  }

  public void testAsMapDuringLoading() throws InterruptedException, ExecutionException {
    final CountDownLatch getStartedSignal = new CountDownLatch(2);
    final CountDownLatch letGetFinishSignal = new CountDownLatch(1);
    final CountDownLatch getFinishedSignal = new CountDownLatch(2);
    final String getKey = "get";
    final String refreshKey = "refresh";
    final String suffix = "Suffix";

    CacheLoader<String, String> computeFunction = new CacheLoader<String, String>() {
      @Override
      public String load(String key) {
        getStartedSignal.countDown();
        assertTrue(Uninterruptibles.awaitUninterruptibly(letGetFinishSignal, 300, TimeUnit.SECONDS));
        return key + suffix;
      }
    };

    final LoadingCache<String, String> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .initialCapacity(1000)
        .executor(MoreExecutors.directExecutor()), computeFunction);
    ConcurrentMap<String,String> map = cache.asMap();
    map.put(refreshKey, refreshKey);
    assertEquals(1, map.size());
    assertFalse(map.containsKey(getKey));
    assertSame(refreshKey, map.get(refreshKey));

    new Thread(() -> {
      cache.getUnchecked(getKey);
      getFinishedSignal.countDown();
    }).start();
    new Thread(() -> {
      cache.refresh(refreshKey);
      getFinishedSignal.countDown();
    }).start();

    assertTrue(getStartedSignal.await(300, TimeUnit.SECONDS));

    // computation is in progress; asMap shouldn't have changed
    assertEquals(1, map.size());
    assertFalse(map.containsKey(getKey));
    assertSame(refreshKey, map.get(refreshKey));

    // let computation complete
    letGetFinishSignal.countDown();
    assertTrue(getFinishedSignal.await(300, TimeUnit.SECONDS));
    checkNothingLogged();

    // asMap view should have been updated
    assertEquals(2, cache.size());
    assertEquals(getKey + suffix, map.get(getKey));
    assertEquals(refreshKey + suffix, map.get(refreshKey));
  }

  // ConcurrentHashMap does not support this, as it must return back the removed entry
  public void disabled_testInvalidateDuringLoading() throws InterruptedException, ExecutionException {
    // computation starts; invalidate() is called on the key being computed, computation finishes
    final CountDownLatch computationStarted = new CountDownLatch(2);
    final CountDownLatch letGetFinishSignal = new CountDownLatch(1);
    final CountDownLatch getFinishedSignal = new CountDownLatch(2);
    final String getKey = "get";
    final String refreshKey = "refresh";
    final String suffix = "Suffix";

    CacheLoader<String, String> computeFunction = new CacheLoader<String, String>() {
      @Override
      public String load(String key) {
        computationStarted.countDown();
        assertTrue(Uninterruptibles.awaitUninterruptibly(letGetFinishSignal, 300, TimeUnit.SECONDS));
        return key + suffix;
      }
    };

    final LoadingCache<String, String> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder(), computeFunction);
    ConcurrentMap<String,String> map = cache.asMap();
    map.put(refreshKey, refreshKey);

    new Thread(() -> {
      cache.getUnchecked(getKey);
      getFinishedSignal.countDown();
    }).start();
    new Thread(() -> {
      cache.refresh(refreshKey);
      getFinishedSignal.countDown();
    }).start();

    assertTrue(computationStarted.await(300, TimeUnit.SECONDS));
    cache.invalidate(getKey);
    cache.invalidate(refreshKey);
    assertFalse(map.containsKey(getKey));
    assertFalse(map.containsKey(refreshKey));

    // let computation complete
    letGetFinishSignal.countDown();
    assertTrue(getFinishedSignal.await(300, TimeUnit.SECONDS));
    checkNothingLogged();

    // results should be visible
    assertEquals(2, cache.size());
    assertEquals(getKey + suffix, map.get(getKey));
    assertEquals(refreshKey + suffix, map.get(refreshKey));
    assertEquals(2, cache.size());
  }

  // ConcurrentHashMap does not support this, as it must return back the removed entry
  public void disabled_testInvalidateAndReloadDuringLoading()
      throws InterruptedException, ExecutionException {
    // computation starts; clear() is called, computation finishes
    final CountDownLatch computationStarted = new CountDownLatch(2);
    final CountDownLatch letGetFinishSignal = new CountDownLatch(1);
    final CountDownLatch getFinishedSignal = new CountDownLatch(4);
    final String getKey = "get";
    final String refreshKey = "refresh";
    final String suffix = "Suffix";

    CacheLoader<String, String> computeFunction = new CacheLoader<String, String>() {
      @Override
      public String load(String key) {
        computationStarted.countDown();
        assertTrue(Uninterruptibles.awaitUninterruptibly(letGetFinishSignal, 300, TimeUnit.SECONDS));
        return key + suffix;
      }
    };

    final LoadingCache<String, String> cache = CaffeinatedGuava.build(
        Caffeine.newBuilder(), computeFunction);
    ConcurrentMap<String,String> map = cache.asMap();
    map.put(refreshKey, refreshKey);

    new Thread(() -> {
      cache.getUnchecked(getKey);
      getFinishedSignal.countDown();
    }).start();
    new Thread(() -> {
      cache.refresh(refreshKey);
      getFinishedSignal.countDown();
    }).start();

    assertTrue(computationStarted.await(300, TimeUnit.SECONDS));
    cache.invalidate(getKey);
    cache.invalidate(refreshKey);
    assertFalse(map.containsKey(getKey));
    assertFalse(map.containsKey(refreshKey));

    // start new computations
    new Thread(() -> {
      cache.getUnchecked(getKey);
      getFinishedSignal.countDown();
    }).start();
    new Thread(() -> {
      cache.refresh(refreshKey);
      getFinishedSignal.countDown();
    }).start();

    // let computation complete
    letGetFinishSignal.countDown();
    assertTrue(getFinishedSignal.await(300, TimeUnit.SECONDS));
    checkNothingLogged();

    // results should be visible
    assertEquals(2, cache.size());
    assertEquals(getKey + suffix, map.get(getKey));
    assertEquals(refreshKey + suffix, map.get(refreshKey));
  }

  static <T> Callable<T> throwing(final Exception exception) {
    return new Callable<T>() {
      @Override public T call() throws Exception {
        throw exception;
      }
    };
  }
}
