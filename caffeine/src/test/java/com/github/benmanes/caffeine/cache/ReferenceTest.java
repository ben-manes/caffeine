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

import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyEvictionListener;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyListeners;
import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.testing.GcFinalization;

/**
 * The test cases for caches that support a reference eviction policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@SuppressWarnings("BoxedPrimitiveConstructor")
@Test(groups = "slow", dataProviderClass = CacheProvider.class)
public final class ReferenceTest {

  // These tests require that the JVM uses -XX:SoftRefLRUPolicyMSPerMB=0 so that soft references
  // can be reliably garbage collected (by making them behave as weak references).

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.FULL)
  public void identity_keys(Cache<Integer, Integer> cache, CacheContext context) {
    @SuppressWarnings("deprecation")
    Integer key = new Integer(context.firstKey());
    assertThat(cache.getIfPresent(key), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = {ReferenceType.WEAK, ReferenceType.SOFT}, population = Population.FULL)
  public void identity_values(Cache<Integer, Integer> cache, CacheContext context) {
    @SuppressWarnings("deprecation")
    Integer value = new Integer(context.original().get(context.firstKey()));
    assertThat(cache.asMap().containsValue(value), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true,
      population = Population.FULL, evictionListener = Listener.MOCKITO)
  public void collect_evictionListenerFails(Cache<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();

    doThrow(RuntimeException.class)
        .when(context.evictionListener()).onRemoval(any(), any(), any());
    awaitFullCleanup(cache);
    verify(context.evictionListener(), times((int) context.initialSize()))
        .onRemoval(any(), any(), any());
  }

  /* --------------- Cache --------------- */

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

    awaitFullCleanup(cache);
    long count = context.initialSize() - cache.estimatedSize() + 1;
    assertThat(count, is(greaterThan(1L)));

    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
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
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void put(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.removalNotifications().clear();

    context.clear();
    GcFinalization.awaitFullGc();

    awaitFullCleanup(cache);
    assertThat(cache.estimatedSize(), is(0L));

    long count = context.initialSize() + 1;
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putAll(Cache<Integer, Integer> cache, CacheContext context) {
    Map<Integer, Integer> entries = ImmutableMap.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.absentKey(), context.absentValue());
    cache.putAll(entries);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    assertThat(cache.estimatedSize(), is(3L));

    long count = context.initialSize() - 2;
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void invalidate(Cache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = cache.getIfPresent(key);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    assertThat(cache.estimatedSize(), is(1L));

    cache.invalidate(key);
    assertThat(value, is(notNullValue()));
    assertThat(cache.estimatedSize(), is(0L));

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
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
    awaitFullCleanup(cache);
    cache.invalidateAll(keys);
    assertThat(cache.estimatedSize(), is(0L));

    long count = context.initialSize() - (context.isWeakKeys() ? keys.size() : 0);
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void invalidateAll_full(Cache<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();

    awaitFullCleanup(cache);
    assertThat(cache.estimatedSize(), is(0L));

    cache.invalidateAll();
    verifyListeners(context, verifier -> verifier.hasCount(0L, RemovalCause.EXPLICIT));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void cleanUp(Cache<Integer, Integer> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();

    awaitFullCleanup(cache);
    assertThat(cache.estimatedSize(), is(0L));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      population = Population.FULL, weigher = CacheWeigher.DEFAULT, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void get(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    assertThat(cache.get(key), is(value));

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      loader = {Loader.NEGATIVE, Loader.BULK_NEGATIVE}, removalListener = Listener.CONSUMING)
  public void getAll(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);

    assertThat(cache.getAll(keys), is(Maps.toMap(keys, key -> -key)));
    long count = context.initialSize() - (context.isStrongValues() ? keys.size() : 0);

    assertThat(cache.estimatedSize(), is((long) keys.size()));
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      loader = Loader.IDENTITY, removalListener = Listener.CONSUMING)
  public void refresh(LoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    cache.refresh(key).join();
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(cache.getIfPresent(key), is(not(value)));

    long count = context.initialSize() - 1;
    verifyRemovalListener(context, verifier -> verifier.hasCount(1, RemovalCause.REPLACED));
    verifyEvictionListener(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  /* --------------- AsyncLoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void getIfPresent(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache.synchronous());
    assertThat(cache.synchronous().getIfPresent(key), is(value));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, loader = Loader.IDENTITY,
      removalListener = Listener.CONSUMING)
  public void get(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(cache.get(context.absentKey()), is(futureOf(context.absentKey())));

    long count = context.initialSize();
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED,
      loader = {Loader.NEGATIVE, Loader.BULK_NEGATIVE}, removalListener = Listener.CONSUMING)
  public void getAll(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = ImmutableSet.of(context.firstKey(), context.lastKey(), context.absentKey());
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAll(keys).join(),
        is(keys.stream().collect(toMap(Function.identity(), key -> -key))));

    long count = context.initialSize() - cache.synchronous().estimatedSize() + 1;

    assertThat(count, is(greaterThan((long) keys.size())));
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED,  removalListener = Listener.CONSUMING)
  public void put(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Integer key = context.absentKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.put(key, CompletableFuture.completedFuture(context.absentValue()));

    long count = context.initialSize();
    assertThat(cache.synchronous().estimatedSize(), is(1L));
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void isEmpty(Map<Integer, Integer> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.isEmpty(), is(false));

    awaitFullCleanup(context.cache());
    assertThat(map.isEmpty(), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void size(Map<Integer, Integer> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.size(), is((int) context.initialSize()));

    awaitFullCleanup(context.cache());
    assertThat(map.size(), is(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void containsKey(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.containsKey(key), is(context.isStrongValues()));
  }

  @Test(dataProvider = "caches")
  @SuppressWarnings("UnusedVariable")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void containsValue(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.original().get(key);
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.containsValue(value), is(true));

    key = null;
    GcFinalization.awaitFullGc();
    assertThat(map.containsValue(value), is(not(context.isWeakKeys())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void clear(Map<Integer, Integer> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    awaitFullCleanup(context.cache());
    long count = context.initialSize();

    assertThat(map.size(), is(0));
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT, population = Population.FULL,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.putIfAbsent(key, context.absentValue()),
        is(context.isStrongValues() ? notNullValue() : nullValue()));

    awaitFullCleanup(context.cache());
    assertThat(map.get(key), is(notNullValue()));
    assertThat(map.size(), is(1));

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void put(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.put(key, context.absentValue()),
        is(context.isStrongValues() ? notNullValue() : nullValue()));

    awaitFullCleanup(context.cache());
    assertThat(map.get(key), is(notNullValue()));
    assertThat(map.size(), is(1));

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void replace(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();

    assertThat(map.replace(key, context.absentValue()), is(
        context.isStrongValues() ? notNullValue() : nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void replaceConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.original().get(key);

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.replace(key, value, context.absentValue()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void remove(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.remove(key), is(context.isStrongValues() ? notNullValue() : nullValue()));

    awaitFullCleanup(context.cache());
    assertThat(map.size(), is(0));

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void removeConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.remove(key, value), is(true));

    awaitFullCleanup(context.cache());
    assertThat(map.size(), is(0));

    long count = context.initialSize() - 1;
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void computeIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.computeIfAbsent(key, k -> context.absentValue()),
        context.isStrongValues() ? not(context.absentValue()) : is(context.absentValue()));

    awaitFullCleanup(context.cache());
    assertThat(map.size(), is(1));

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void computeIfPresent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.computeIfPresent(key, (k, v) -> context.absentValue()),
        context.isStrongValues() ? is(context.absentValue()) : is(nullValue()));

    awaitFullCleanup(context.cache());
    assertThat(map.size(), is(context.isStrongValues() ? 1 : 0));

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void compute(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();

    assertThat(map.compute(key, (k, v) -> {
      assertThat(v, is(context.isStrongValues() ? notNullValue() : nullValue()));
      return context.absentValue();
    }), is(context.absentValue()));

    awaitFullCleanup(context.cache());
    assertThat(map.size(), is(1));

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void merge(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.merge(key, context.absentValue(), (oldValue, v) -> {
      if (context.isWeakKeys() && !context.isStrongValues()) {
        throw new AssertionError("Should never be called");
      }
      return context.absentValue();
    }), is(context.absentValue()));

    awaitFullCleanup(context.cache());
    assertThat(map.size(), is(1));

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    verifyListeners(context, verifier -> verifier.hasCount(count, RemovalCause.COLLECTED));
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

  /* --------------- Weights --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();

    assertThat(cache.asMap().putIfAbsent(key, ImmutableList.of(1, 2, 3)),
        context.isStrongValues() ? is(ImmutableList.of(1)) : nullValue());
    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(),
        is(context.isStrongValues() ? 1L : 3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void put_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();

    assertThat(cache.asMap().put(key, ImmutableList.of(1, 2, 3)),
        context.isStrongValues() ? is(ImmutableList.of(1)) : nullValue());
    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void computeIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();

    cache.asMap().computeIfAbsent(key, k -> ImmutableList.of(1, 2, 3));
    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(),
        is(context.isStrongValues() ? 1L : 3L));
    }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void compute_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();

    cache.asMap().compute(key, (k, v) -> ImmutableList.of(1, 2, 3));
    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void merge_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    Integer key = context.absentKey();
    cache.put(key, ImmutableList.of(1));
    GcFinalization.awaitFullGc();

    cache.asMap().merge(key, ImmutableList.of(1, 2, 3), (oldValue, v) -> {
      if (context.isWeakKeys() && !context.isStrongValues()) {
        throw new AssertionError("Should never be called");
      }
      return v;
    });

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, population = Population.FULL,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DEFAULT,
      stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void keySetToArray(Map<Integer, Integer> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.keySet().toArray(), arrayWithSize(0));
    assertThat(map.keySet().toArray(new Integer[0]), arrayWithSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, population = Population.FULL,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DEFAULT,
      stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void valuesToArray(Map<Integer, Integer> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.values().toArray(), arrayWithSize(0));
    assertThat(map.values().toArray(new Integer[0]), arrayWithSize(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, population = Population.FULL,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DEFAULT,
      stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void entrySetToArray(Map<Integer, Integer> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.entrySet().toArray(), arrayWithSize(0));
    assertThat(map.entrySet().toArray(new Map.Entry<?, ?>[0]), arrayWithSize(0));
  }

  /** Ensures that that all the pending work is performed (Guava limits work per cycle). */
  private static void awaitFullCleanup(Cache<?, ?> cache) {
    for (;;) {
      long size = cache.estimatedSize();
      cache.cleanUp();

      if (size == cache.estimatedSize()) {
        break;
      }
    }
  }
}
