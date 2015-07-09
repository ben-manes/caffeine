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

import static com.github.benmanes.caffeine.cache.testing.CacheWriterVerifier.verifyWriter;
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Expiration;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.ExpireAfterWrite;
import com.github.benmanes.caffeine.cache.testing.RejectingCacheWriter.DeleteException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;

/**
 * The test cases for caches that support the expire after write (time-to-live) policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class ExpireAfterWriteTest {

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.IMMEDIATELY, population = Population.EMPTY)
  public void expire_zero(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    assertThat(cache.estimatedSize(), is(0L));
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPIRED));
  }

  /* ---------------- Cache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getIfPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void getIfPresent_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.getIfPresent(context.firstKey());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void get(Cache<Integer, Integer> cache, CacheContext context) {
    Function<Integer, Integer> mappingFunction = context.original()::get;
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey(), mappingFunction);
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.get(context.lastKey(), mappingFunction); // recreated

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void get_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.get(context.firstKey(), Function.identity());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getAllPresent(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getAllPresent(context.firstMiddleLastKeys());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getAllPresent(context.firstMiddleLastKeys()).size(), is(0));

    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(0L));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }


  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void put_insert(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(context.firstKey(), context.absentValue());

    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void put_replace(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.consumedNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.estimatedSize(), is(2L));

    long count = context.initialSize() - 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void put_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.put(context.firstKey(), context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void putAll_insert(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.putAll(ImmutableMap.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.lastKey(), context.absentValue()));

    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(3L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void putAll_replace(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.putAll(ImmutableMap.of(
        context.firstKey(), context.absentValue(),
        context.absentKey(), context.absentValue()));
    context.consumedNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.estimatedSize(), is(2L));

    long count = context.initialSize() - 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void putAll_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.putAll(ImmutableMap.of(context.firstKey(), context.absentValue()));
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void invalidate(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidate(context.firstKey());

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidate_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.invalidate(context.firstKey());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void invalidateAll(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidateAll(context.firstMiddleLastKeys());

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidateAll_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.invalidateAll(context.firstMiddleLastKeys());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void invalidateAll_full(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidateAll();

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidateAll_full_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.invalidateAll();
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void estimatedSize(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void cleanUp(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.cleanUp();

    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(0L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void cleanUp_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.cleanUp();
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  /* ---------------- LoadingCache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void get(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.get(context.firstKey());
    cache.get(context.absentKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(cache.getIfPresent(context.absentKey()), is(-context.absentKey()));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void get_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.get(context.firstKey());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = { Population.PARTIAL, Population.FULL })
  public void getAll(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getAll(ImmutableList.of(context.firstKey(), context.absentKey())),
        is(ImmutableMap.of(context.firstKey(), -context.firstKey(),
            context.absentKey(), context.absentKey())));

    context.ticker().advance(45, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.getAll(ImmutableList.of(context.firstKey(), context.absentKey())),
        is(ImmutableMap.of(context.firstKey(), context.firstKey(),
            context.absentKey(), context.absentKey())));
    assertThat(cache.estimatedSize(), is(2L));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void getAll_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.getAll(context.firstMiddleLastKeys());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = Population.FULL)
  public void refresh(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    Integer key = context.firstKey();
    cache.refresh(key);

    long count = (cache.estimatedSize() == 1) ? context.initialSize() : 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> {
      verifier.deleted(key, context.original().get(key), RemovalCause.EXPIRED);
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void refresh_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.HOURS);
    cache.refresh(context.firstKey());
    context.disableRejectingCacheWriter();
    context.ticker().advance(-1, TimeUnit.HOURS);
    assertThat(cache.asMap(), equalTo(context.original()));
  }

  /* ---------------- AsyncLoadingCache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.PARTIAL, Population.FULL })
  public void getIfPresent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    cache.getIfPresent(context.firstKey());
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(nullValue()));
    assertThat(cache.getIfPresent(context.lastKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(0L));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = Population.FULL, removalListener = Listener.CONSUMING)
  public void get(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.SECONDS);

    cache.get(context.firstKey());
    cache.get(context.middleKey(), k -> context.absentValue());
    cache.get(context.lastKey(), (k, executor) ->
        CompletableFuture.completedFuture(context.absentValue()));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE,
      population = Population.EMPTY, removalListener = Listener.CONSUMING)
  public void get_async(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> future = new CompletableFuture<Integer>();
    cache.get(context.absentKey(), (k, e) -> future);
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.synchronous().cleanUp();

    assertThat(cache, hasRemovalNotifications(context, 0, RemovalCause.EXPIRED));
    future.complete(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.absentKey()), is(future));

    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));

    cache.synchronous().cleanUp();
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(1, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, loader = Loader.IDENTITY,
      population = Population.FULL, removalListener = Listener.CONSUMING)
  public void getAll(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.SECONDS);
    cache.getAll(context.firstMiddleLastKeys());

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void put_insert(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(context.firstKey(), CompletableFuture.completedFuture(context.absentValue()));

    long count = context.initialSize();
    assertThat(cache.synchronous().estimatedSize(), is(1L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void put_replace(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> future = CompletableFuture.completedFuture(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), future);
    cache.put(context.absentKey(), future);
    context.consumedNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(futureOf(context.absentValue())));
    assertThat(cache.getIfPresent(context.absentKey()), is(futureOf(context.absentValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(2L));

    long count = context.initialSize() - 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  /* ---------------- Map -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void isEmpty(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.isEmpty(), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void containsKey(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.containsKey(context.firstKey()), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void containsValue(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.containsValue(context.original().get(context.firstKey())), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void clear(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    map.clear();

    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void clear_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      map.clear();
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void putIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.putIfAbsent(context.firstKey(), context.absentValue()), is(not(nullValue())));

    context.ticker().advance(30, TimeUnit.SECONDS);
    map.putIfAbsent(context.lastKey(), context.absentValue());

    long count = context.initialSize();
    assertThat(map.size(), is(1));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void putIfAbsent_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      map.putIfAbsent(context.firstKey(), context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void put_insert(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    map.put(context.firstKey(), context.absentValue());

    long count = context.initialSize();
    assertThat(map.size(), is(1));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void put_replace(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    map.put(context.firstKey(), context.absentValue());
    map.put(context.absentKey(), context.absentValue());
    context.consumedNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(map.get(context.firstKey()), is(context.absentValue()));
    assertThat(map.get(context.absentKey()), is(context.absentValue()));
    assertThat(map.get(context.middleKey()), is(nullValue()));
    assertThat(map.size(), is(2));

    long count = context.initialSize() - 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void put_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      map.put(context.firstKey(), context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void replace(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(60, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue()), is(nullValue()));

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map.size(), is(0));
    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void replace_updated(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue()), is(not(nullValue())));
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map.size(), is(1));
    long count = context.initialSize() - 1;
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count));
  }

  // replace_writerFail: Not needed due to exiting without side-effects

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void replaceConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(60, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue()), is(false));

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map.size(), is(0));
    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void replaceConditionally_updated(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue()), is(true));
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map.size(), is(1));
    long count = context.initialSize() - 1;
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count));
  }

  // replaceConditionally_writerFail: Not needed due to exiting without side-effects

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void remove(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.remove(context.firstKey()), is(nullValue()));

    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void remove_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      map.remove(context.firstKey());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void removeConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.remove(key, context.original().get(key)), is(false));

    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void removeConditionally_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.remove(key, context.original().get(key));
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void computeIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.computeIfAbsent(key, k -> context.absentValue()), is(context.absentValue()));

    assertThat(map.size(), is(1));
    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void computeIfAbsent_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.computeIfAbsent(key, k -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void computeIfPresent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.computeIfPresent(key, (k, v) -> value), is(nullValue()));

    assertThat(map.size(), is(0));
    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void computeIfPresent_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.computeIfPresent(key, (k, v) -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void compute(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.compute(key, (k, v) -> {
      assertThat(v, is(nullValue()));
      return value;
    }), is(value));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void compute_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.compute(key, (k, v) -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void merge(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.merge(key, value, (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    }), is(value));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void merge_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      Integer value = context.absentValue();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.merge(key, value, (oldValue, v) -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(expireAfterWrite = Expire.ONE_MINUTE, population = Population.FULL)
  public void iterators(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(Iterators.size(map.keySet().iterator()), is(0));
    assertThat(Iterators.size(map.values().iterator()), is(0));
    assertThat(Iterators.size(map.entrySet().iterator()), is(0));
  }

  /* ---------------- Weights -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION)
  public void put_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(1, ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION)
  public void putIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().putIfAbsent(1, ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION)
  public void computeIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().computeIfAbsent(1, k -> ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION)
  public void compute_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().compute(1, (k, v) -> ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = Population.EMPTY, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION)
  public void merge_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().merge(1, ImmutableList.of(1, 2, 3), (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    });

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  /* ---------------- Policy -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void getExpiresAfter(
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void setExpiresAfter(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.setExpiresAfter(2, TimeUnit.MINUTES);
    assertThat(expireAfterWrite.getExpiresAfter(TimeUnit.MINUTES), is(2L));

    context.ticker().advance(90, TimeUnit.SECONDS);
    cache.cleanUp();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void ageOf(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(0L));
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).getAsLong(), is(30L));
    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(expireAfterWrite.ageOf(context.firstKey(), TimeUnit.SECONDS).isPresent(), is(false));
  }

  /* ---------------- Policy: oldest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void oldest_unmodifiable(
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.oldest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void oldest_negative(@ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.oldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_zero(@ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.oldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_partial(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterWrite.oldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void oldest_order(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    assertThat(Iterables.elementsEqual(oldest.keySet(), context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void oldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> oldest = expireAfterWrite.oldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(oldest, is(equalTo(context.original())));
  }

  /* ---------------- Policy: youngest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void youngest_unmodifiable(
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.youngest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void youngest_negative(@ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    expireAfterWrite.youngest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_zero(@ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    assertThat(expireAfterWrite.youngest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_partial(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    int count = (int) context.initialSize() / 2;
    assertThat(expireAfterWrite.youngest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, expireAfterWrite = Expire.ONE_MINUTE,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void youngest_order(CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(youngest.keySet()).reverse());
    assertThat(Iterables.elementsEqual(keys, context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, expireAfterWrite = Expire.ONE_MINUTE)
  public void youngest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      @ExpireAfterWrite Expiration<Integer, Integer> expireAfterWrite) {
    Map<Integer, Integer> youngest = expireAfterWrite.youngest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(youngest, is(equalTo(context.original())));
  }
}
