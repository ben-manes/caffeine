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

package com.github.benmanes.caffeine.guava.compatibility;

import static com.github.benmanes.caffeine.guava.compatibility.CacheTesting.checkEmpty;
import static com.github.benmanes.caffeine.guava.compatibility.TestingCacheLoaders.constantLoader;
import static com.github.benmanes.caffeine.guava.compatibility.TestingCacheLoaders.exceptionLoader;
import static com.github.benmanes.caffeine.guava.compatibility.TestingRemovalListeners.queuingRemovalListener;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.guava.CaffeinatedGuava;
import com.github.benmanes.caffeine.guava.compatibility.TestingRemovalListeners.QueuingRemovalListener;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.UncheckedExecutionException;

import junit.framework.TestCase;

/**
 * {@link LoadingCache} tests for caches with a maximum size of zero.
 *
 * @author mike nonemacher
 */
@SuppressWarnings("PreferJavaTimeOverload")
public class NullCacheTest extends TestCase {
  QueuingRemovalListener<Object, Object> listener;

  @Override
  protected void setUp() {
    listener = queuingRemovalListener();
  }

  public void testGet() {
    Object computed = new Object();
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .maximumSize(0)
        .removalListener(listener),
        constantLoader(computed));

    Object key = new Object();
    assertSame(computed, cache.getUnchecked(key));
    RemovalNotification<Object, Object> notification = listener.remove();
    assertSame(key, notification.getKey());
    assertSame(computed, notification.getValue());
    assertSame(RemovalCause.SIZE, notification.getCause());
    assertTrue(listener.isEmpty());
    checkEmpty(cache);
  }

  public void testGet_expireAfterWrite() {
    // Guava sends a notification with SIZE as the removal cause by redefining 0 expiration as
    // a maximum size of zero. This is not done as expiration can be dynamically updated

    Object computed = new Object();
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .expireAfterWrite(0, SECONDS)
        .removalListener(listener),
        constantLoader(computed));

    Object key = new Object();
    assertSame(computed, cache.getUnchecked(key));
    RemovalNotification<Object, Object> notification = listener.remove();
    assertSame(key, notification.getKey());
    assertSame(computed, notification.getValue());
    assertSame(RemovalCause.EXPIRED, notification.getCause());
    assertTrue(listener.isEmpty());
    checkEmpty(cache);
  }

  public void testGet_expireAfterAccess() {
    // Guava sends a notification with SIZE as the removal cause by redefining 0 expiration as
    // a maximum size of zero. This is not done as expiration can be dynamically updated

    Object computed = new Object();
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .executor(MoreExecutors.directExecutor())
        .expireAfterAccess(0, SECONDS)
        .removalListener(listener),
        constantLoader(computed));

    Object key = new Object();
    assertSame(computed, cache.getUnchecked(key));
    RemovalNotification<Object, Object> notification = listener.remove();
    assertSame(key, notification.getKey());
    assertSame(computed, notification.getValue());
    assertSame(RemovalCause.EXPIRED, notification.getCause());
    assertTrue(listener.isEmpty());
    checkEmpty(cache);
  }

  public void testGet_computeNull() {
    LoadingCache<Object, Object> cache = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumSize(0)
        .removalListener(listener),
        constantLoader(null));

    try {
      cache.getUnchecked(new Object());
      fail();
    } catch (InvalidCacheLoadException e) { /* expected */}

    assertTrue(listener.isEmpty());
    checkEmpty(cache);
  }

  public void testGet_runtimeException() {
    final RuntimeException e = new RuntimeException();
    LoadingCache<Object, Object> map = CaffeinatedGuava.build(Caffeine.newBuilder()
        .maximumSize(0)
        .removalListener(listener),
        exceptionLoader(e));

    try {
      map.getUnchecked(new Object());
      fail();
    } catch (UncheckedExecutionException uee) {
      assertSame(e, uee.getCause());
    }
    assertTrue(listener.isEmpty());
    checkEmpty(map);
  }
}
