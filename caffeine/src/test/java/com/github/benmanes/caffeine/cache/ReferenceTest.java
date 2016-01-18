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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.testing.CacheWriterVerifier.verifyWriter;
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.RejectingCacheWriter.DeleteException;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.testing.GcFinalization;

/**
 * The test cases for caches that support a reference eviction policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(groups = "slow", dataProviderClass = CacheProvider.class)
public final class ReferenceTest {
  // These tests require that the JVM uses -XX:SoftRefLRUPolicyMSPerMB=0 so that soft references
  // can be reliably garbage collected (by making them behave as weak references).

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.FULL)
  public void identity_keys(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = new Integer(context.firstKey());
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = {ReferenceType.WEAK, ReferenceType.SOFT}, population = Population.FULL)
  public void identity_values(Cache<Integer, Integer> cache, CacheContext context) {
    Integer value = new Integer(context.original().get(context.firstKey()));
    assertThat(cache.asMap().containsValue(value), is(false));
  }

  /* ---------------- Cache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getIfPresent(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void get(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.get(key, k -> context.absentValue()), is(context.absentValue()));

    long count = context.initialSize() - cache.estimatedSize() + 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void get_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.get(key, Function.identity());
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getAllPresent(Cache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAllPresent(keys), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void put(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.put(key, context.absentValue());

    long count = context.initialSize() - cache.estimatedSize() + 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void put_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.put(key, context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putAll(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> entries = ImmutableMap.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.lastKey(), context.absentValue());
    context.clear();
    GcFinalization.awaitFullGc();
    cache.putAll(entries);

    long count = context.initialSize() - cache.estimatedSize() + 3;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void putAll_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.putAll(ImmutableMap.of(key, context.absentValue()));
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void invalidate(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.invalidate(key);

    long count = context.initialSize() - cache.estimatedSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void invalidate_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.invalidate(key);
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void invalidateAll(Cache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.invalidateAll(keys);

    long count = context.initialSize() - cache.estimatedSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void invalidateAll_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.invalidateAll(keys);
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void invalidateAll_full(Cache<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    cache.invalidateAll();

    long count = context.initialSize() - cache.estimatedSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void invalidateAll_full_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.invalidateAll();
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void estimatedSize(Cache<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void cleanUp(Cache<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    cache.cleanUp();

    long count = context.initialSize() - cache.estimatedSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void cleanUp_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    cache.cleanUp();

    context.disableRejectingCacheWriter();
    assertThat(cache.asMap().isEmpty(), is(false));
  }

  /* ---------------- LoadingCache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.SINGLETON, stats = Stats.ENABLED, loader = Loader.IDENTITY,
      removalListener = Listener.CONSUMING)
  public void get(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.get(key), is(key));

    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(1, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void get_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.get(key);
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED,
      loader = {Loader.IDENTITY, Loader.BULK_IDENTITY}, removalListener = Listener.CONSUMING)
  public void getAll(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAll(keys), is(Maps.uniqueIndex(keys, Functions.identity())));

    long count = context.initialSize() - cache.estimatedSize() + keys.size();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL,
      compute = Compute.SYNC, loader = {Loader.IDENTITY, Loader.BULK_IDENTITY})
  public void getAll_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.getAll(keys);
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, loader = Loader.IDENTITY,
      removalListener = Listener.CONSUMING)
  public void refresh(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.refresh(key);

    long count = context.initialSize() - cache.estimatedSize() + 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> {
      verifier.deleted(key, context.original().get(key), RemovalCause.COLLECTED);
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void refresh_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.refresh(key);
    context.disableRejectingCacheWriter();
    assertThat(cache.asMap().isEmpty(), is(false));
  }

  /* ---------------- AsyncLoadingCache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getIfPresent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.SINGLETON, stats = Stats.ENABLED, loader = Loader.IDENTITY,
      removalListener = Listener.CONSUMING)
  public void get(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.get(key), is(futureOf(key)));

    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(1, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void get_writerFails(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.get(key);
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.synchronous().asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED,
      loader = {Loader.IDENTITY, Loader.BULK_IDENTITY}, removalListener = Listener.CONSUMING)
  public void getAll(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAll(keys).join(), is(Maps.uniqueIndex(keys, Functions.identity())));

    long count = context.initialSize() - cache.synchronous().estimatedSize() + keys.size();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void getAll_writerFails(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      cache.getAll(keys);
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.synchronous().asMap().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED,  removalListener = Listener.CONSUMING)
  public void put(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.put(key, CompletableFuture.completedFuture(context.absentValue()));

    long count = context.initialSize();
    assertThat(cache.synchronous().estimatedSize(), is(1L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
  }

  /* ---------------- Map -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void isEmpty(Map<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.isEmpty(), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void size(Map<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.size(), is((int) context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void containsKey(Map<Integer, Integer> map, CacheContext context) {
    Integer first = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.containsKey(first), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void containsValue(Map<Integer, Integer> map, CacheContext context) {
    Integer value = context.original().get(context.firstKey());
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.containsValue(value), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void clear(Map<Integer, Integer> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    map.clear();

    long count = context.initialSize() - map.size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void clear_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      map.clear();
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(map.keySet().isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.putIfAbsent(key, context.absentValue()), is(nullValue()));
    assertThat(map.get(key), is(context.absentValue()));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void putIfAbsent_writerFails(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      map.putIfAbsent(key, context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(map.isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void put(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.put(key, context.absentValue()), is(nullValue()));
    assertThat(map.get(key), is(context.absentValue()));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void put_writerFails(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      map.put(key, context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(map.isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void replace(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.replace(key, context.absentValue()), is(nullValue()));
  }

  // replace_writerFail: Not needed due to replacement being impossible

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void replaceConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.replace(key, context.absentValue(), context.absentValue()), is(false));
  }

  // replace_writerFail: Not needed due to replacement being impossible

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void remove(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.remove(key), is(nullValue()));

    long count = context.initialSize() - map.size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void remove_writerFails(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      map.remove(key);
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(map.isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void removeConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.remove(key, context.absentValue()), is(false));

    long count = context.initialSize() - map.size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  // removeConditionally_writerFail: Not needed due to removal being impossible

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void computeIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.computeIfAbsent(key, k -> context.absentValue()), is(context.absentValue()));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void computeIfAbsent_writerFails(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      map.computeIfAbsent(key, k -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(map.isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void computeIfPresent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.computeIfPresent(key, (k, v) -> context.absentValue()), is(nullValue()));

    long count = context.initialSize() - map.size();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  // computeIfPresent_writerFail: Not needed due to exiting without side-effects

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void compute(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.compute(key, (k, v) -> {
      assertThat(v, is(nullValue()));
      return context.absentValue();
    }), is(context.absentValue()));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void compute_writerFails(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      map.compute(key, (k, v) -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(map.isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void merge(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.merge(key, context.absentValue(), (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    }), is(context.absentValue()));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.COLLECTED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      implementation = Implementation.Caffeine, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      compute = Compute.SYNC, removalListener = Listener.CONSUMING, writer = Writer.EXCEPTIONAL)
  public void merge_writerFails(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    try {
      context.clear();
      GcFinalization.awaitFullGc();
      map.merge(key, context.absentValue(), (k, v) -> v);
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(map.isEmpty(), is(false));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true)
  public void iterators(Map<Integer, Integer> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(Iterators.size(map.keySet().iterator()), is(0));
    assertThat(Iterators.size(map.values().iterator()), is(0));
    assertThat(Iterators.size(map.entrySet().iterator()), is(0));
  }

  /* ---------------- Weights -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      values = {ReferenceType.WEAK, ReferenceType.SOFT}, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.UNREACHABLE,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING, writer = Writer.DISABLED)
  public void putIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();
    assertThat(cache.asMap().putIfAbsent(key, ImmutableList.of(1, 2, 3)), is(nullValue()));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      values = {ReferenceType.WEAK, ReferenceType.SOFT}, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.UNREACHABLE,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY, stats = Stats.ENABLED,
      removalListener = Listener.DEFAULT, writer = Writer.DISABLED)
  public void put_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();
    assertThat(cache.asMap().put(key, ImmutableList.of(1, 2, 3)), is(nullValue()));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      values = {ReferenceType.WEAK, ReferenceType.SOFT}, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.UNREACHABLE,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY, stats = Stats.ENABLED,
      removalListener = Listener.DEFAULT, writer = Writer.DISABLED)
  public void computeIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();
    cache.asMap().computeIfAbsent(1, k -> ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      values = {ReferenceType.WEAK, ReferenceType.SOFT}, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.UNREACHABLE,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY, stats = Stats.ENABLED,
      removalListener = Listener.DEFAULT, writer = Writer.DISABLED)
  public void compute_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();
    cache.asMap().compute(1, (k, v) -> ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      values = {ReferenceType.WEAK, ReferenceType.SOFT}, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.UNREACHABLE,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY, stats = Stats.ENABLED,
      removalListener = Listener.DEFAULT, writer = Writer.DISABLED)
  public void merge_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();
    cache.asMap().merge(1, ImmutableList.of(1, 2, 3), (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    });

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }
}
