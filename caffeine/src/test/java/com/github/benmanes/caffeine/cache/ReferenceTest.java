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

import static com.github.benmanes.caffeine.cache.RemovalCause.COLLECTED;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Map.entry;
import static java.util.function.Function.identity;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
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
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.Int;
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

  // These tests require that the JVM uses -XX:SoftRefLRUPolicyMSPerMB=0 and -XX:+UseParallelGC so
  // that soft references can be reliably garbage collected by making them behave as weak
  // references.

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.FULL)
  public void identity_keys(Cache<Int, Int> cache, CacheContext context) {
    Int key = new Int(context.firstKey());
    assertThat(cache).doesNotContainKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(values = {ReferenceType.WEAK, ReferenceType.SOFT}, population = Population.FULL)
  public void identity_values(Cache<Int, Int> cache, CacheContext context) {
    Int value = new Int(context.original().get(context.firstKey()));
    assertThat(cache).doesNotContainKey(value);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true,
      population = Population.FULL, evictionListener = Listener.MOCKITO)
  public void collect_evictionListenerFails(Cache<Int, Int> cache, CacheContext context) {
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
  public void getIfPresent(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getIfPresent(key)).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void get(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.get(key, k -> context.absentValue())).isEqualTo(context.absentValue());

    awaitFullCleanup(cache);
    long count = context.initialSize() - cache.estimatedSize() + 1;
    assertThat(count).isGreaterThan(1);

    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getAllPresent(Cache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAllPresent(keys)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void getAll(Cache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);

    assertThat(cache.getAll(keys, keysToLoad -> Maps.asMap(keysToLoad, Int::negate)))
        .containsExactlyEntriesIn(Maps.toMap(keys, Int::negate));
    long count = context.initialSize() - (context.isStrongValues() ? keys.size() : 0);

    assertThat(cache).hasSize(keys.size());
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void put(Cache<Int, Int> cache, CacheContext context) {
    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.clearRemovalNotifications();

    context.clear();
    GcFinalization.awaitFullGc();

    awaitFullCleanup(cache);
    assertThat(cache).isEmpty();

    long count = context.initialSize() + 1;
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putAll(Cache<Int, Int> cache, CacheContext context) {
    var entries = Map.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.absentKey(), context.absentValue());
    cache.putAll(entries);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    assertThat(cache).hasSize(3);

    long count = context.initialSize() - 2;
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void invalidate(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = cache.getIfPresent(key);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    assertThat(cache).hasSize(1);

    cache.invalidate(key);
    assertThat(value).isNotNull();
    assertThat(cache).isEmpty();

    long count = context.initialSize() - 1;
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void invalidateAll(Cache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    cache.invalidateAll(keys);
    assertThat(cache).isEmpty();

    long count = context.initialSize() - (context.isWeakKeys() ? keys.size() : 0);
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void invalidateAll_full(Cache<Int, Int> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();

    awaitFullCleanup(cache);
    assertThat(cache).isEmpty();

    cache.invalidateAll();
    assertThat(context).notifications().withCause(COLLECTED).hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void cleanUp(Cache<Int, Int> cache, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();

    awaitFullCleanup(cache);
    assertThat(cache).isEmpty();

    long count = context.initialSize();
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      population = Population.FULL, weigher = CacheWeigher.DEFAULT, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void get_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    assertThat(cache.get(key)).isEqualTo(value);

    long count = context.initialSize() - 1;
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      loader = {Loader.NEGATIVE, Loader.BULK_NEGATIVE}, removalListener = Listener.CONSUMING)
  public void getAll_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);

    assertThat(cache.getAll(keys)).containsExactlyEntriesIn(Maps.toMap(keys, Int::negate));
    long count = context.initialSize() - (context.isStrongValues() ? keys.size() : 0);

    assertThat(cache).hasSize(keys.size());
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      loader = Loader.IDENTITY, removalListener = Listener.CONSUMING)
  public void refresh(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache);
    assertThat(cache.refresh(key)).succeedsWith(key);
    assertThat(cache).doesNotContainEntry(key, value);
    assertThat(cache).hasSize(1);

    long count = context.initialSize() - 1;
    assertThat(context).removalNotifications().withCause(REPLACED).hasSize(1);
    assertThat(context).evictionNotifications().withCause(COLLECTED).hasSize(count);
  }

  /* --------------- AsyncCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void getIfPresent_async(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    awaitFullCleanup(cache.synchronous());
    assertThat(cache.synchronous().getIfPresent(key)).isEqualTo(value);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void get_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(cache.get(context.absentKey(), identity())).succeedsWith(context.absentKey());

    long count = context.initialSize();
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getAll_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = Set.of(context.firstKey(), context.lastKey(), context.absentKey());
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAll(keys, keysToLoad -> Maps.asMap(keysToLoad, Int::negate)).join())
        .containsExactlyEntriesIn(Maps.asMap(keys, Int::negate));

    long count = context.initialSize() - cache.synchronous().estimatedSize() + 1;
    assertThat(count).isGreaterThan(keys.size());
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, values = ReferenceType.STRONG,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT,
      population = Population.FULL, stats = Stats.ENABLED,  removalListener = Listener.CONSUMING)
  public void put_async(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.absentKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.put(key, context.absentValue().asFuture());

    assertThat(cache).hasSize(1);
    long count = context.initialSize();
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void isEmpty(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map).isNotEmpty();

    awaitFullCleanup(context.cache());
    assertThat(map).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void size(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map).hasSize(context.initialSize());

    awaitFullCleanup(context.cache());
    assertThat(map).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void containsKey(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.containsKey(key)).isEqualTo(context.isStrongValues());
  }

  @Test(dataProvider = "caches")
  @SuppressWarnings("UnusedVariable")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void containsValue(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.containsValue(value)).isTrue();

    key = null;
    GcFinalization.awaitFullGc();
    assertThat(map.containsValue(value)).isNotEqualTo(context.isWeakKeys());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void clear(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    awaitFullCleanup(context.cache());
    long count = context.initialSize();

    assertThat(map).isExhaustivelyEmpty();
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DEFAULT, population = Population.FULL,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putIfAbsent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    Int value = map.putIfAbsent(key, context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isNotNull();
    } else {
      assertThat(value).isNull();
    }

    awaitFullCleanup(context.cache());
    assertThat(map).containsKey(key);
    assertThat(map).hasSize(1);

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void put_map(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    Int value = map.put(key, context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isNotNull();
    } else {
      assertThat(value).isNull();
    }

    awaitFullCleanup(context.cache());
    assertThat(map).containsKey(key);
    assertThat(map).hasSize(1);

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void replace(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    Int value = map.replace(key, context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isNotNull();
    } else {
      assertThat(value).isNull();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void replaceConditionally(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.replace(key, value, context.absentValue())).isTrue();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void remove(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    Int value = map.remove(key);
    if (context.isStrongValues()) {
      assertThat(value).isNotNull();
    } else {
      assertThat(value).isNull();
    }

    awaitFullCleanup(context.cache());
    assertThat(map).isExhaustivelyEmpty();

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void removeConditionally(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.remove(key, value)).isTrue();

    awaitFullCleanup(context.cache());
    assertThat(map).isExhaustivelyEmpty();

    long count = context.initialSize() - 1;
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void computeIfAbsent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    Int value = map.computeIfAbsent(key, k -> context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isNotEqualTo(context.absentValue());
    } else {
      assertThat(value).isEqualTo(context.absentValue());
    }

    awaitFullCleanup(context.cache());
    assertThat(map).hasSize(1);

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void computeIfPresent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    Int value = map.computeIfPresent(key, (k, v) -> context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isEqualTo(context.absentValue());
    } else {
      assertThat(value).isNull();
    }

    awaitFullCleanup(context.cache());
    if (context.isStrongValues()) {
      assertThat(map).hasSize(1);
    } else {
      assertThat(map).isExhaustivelyEmpty();
    }

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void compute(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    Int value = map.compute(key, (k, v) -> {
      if (context.isStrongValues()) {
        assertThat(v).isNotNull();
      } else {
        assertThat(v).isNull();
      }
      return context.absentValue();
    });
    assertThat(value).isEqualTo(context.absentValue());

    awaitFullCleanup(context.cache());
    assertThat(map).hasSize(1);

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, expireAfterAccess = Expire.DISABLED,
      expireAfterWrite = Expire.DISABLED, maximumSize = Maximum.DISABLED,
      weigher = CacheWeigher.DEFAULT, population = Population.FULL, stats = Stats.ENABLED,
      removalListener = Listener.CONSUMING)
  public void merge(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    GcFinalization.awaitFullGc();
    Int value = map.merge(key, context.absentValue(), (oldValue, v) -> {
      if (context.isWeakKeys() && !context.isStrongValues()) {
        Assert.fail("Should not be invoked");
      }
      return context.absentValue();
    });
    assertThat(value).isEqualTo(context.absentValue());

    awaitFullCleanup(context.cache());
    assertThat(map).hasSize(1);

    long count = context.initialSize() - (context.isStrongValues() ? 1 : 0);
    assertThat(context).notifications().withCause(COLLECTED).hasSize(count);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true)
  public void iterators(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.keySet().iterator().hasNext()).isFalse();
    assertThat(map.values().iterator().hasNext()).isFalse();
    assertThat(map.entrySet().iterator().hasNext()).isFalse();
  }

  /* --------------- Weights --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putIfAbsent_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    GcFinalization.awaitFullGc();

    var value = cache.asMap().putIfAbsent(key, Int.listOf(1, 2, 3));
    if (context.isStrongValues()) {
      assertThat(value).isEqualTo(Int.listOf(1));
      assertThat(cache).hasWeightedSize(1);
    } else {
      assertThat(value).isNull();
      assertThat(cache).hasWeightedSize(3);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void put_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    GcFinalization.awaitFullGc();

    var value = cache.asMap().put(key, Int.listOf(1, 2, 3));
    if (context.isStrongValues()) {
      assertThat(value).isEqualTo(Int.listOf(1));
    } else {
      assertThat(value).isNull();
    }
    assertThat(cache).hasWeightedSize(3);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void computeIfAbsent_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    GcFinalization.awaitFullGc();

    cache.asMap().computeIfAbsent(key, k -> Int.listOf(1, 2, 3));
    if (context.isStrongValues()) {
      assertThat(cache).hasWeightedSize(1);
    } else {
      assertThat(cache).hasWeightedSize(3);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void compute_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    GcFinalization.awaitFullGc();

    cache.asMap().compute(key, (k, v) -> Int.listOf(1, 2, 3));
    assertThat(cache).hasWeightedSize(3);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      population = Population.EMPTY, stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void merge_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    GcFinalization.awaitFullGc();

    cache.asMap().merge(key, Int.listOf(1, 2, 3), (oldValue, v) -> {
      if (context.isWeakKeys() && !context.isStrongValues()) {
        Assert.fail("Should never be called");
      }
      return v;
    });
    assertThat(cache).hasWeightedSize(3);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, population = Population.FULL,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DEFAULT,
      stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void keySetToArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.keySet().toArray()).isEmpty();
    assertThat(map.keySet().toArray(new Int[0])).isEmpty();
    assertThat(map.keySet().toArray(Int[]::new)).isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void keySet_contains(Map<Int, Int> map, CacheContext context) {
    assertThat(map.keySet().contains(new Int(context.firstKey()))).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, population = Population.FULL,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DEFAULT,
      stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void valuesToArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.values().toArray()).isEmpty();
    assertThat(map.values().toArray(new Int[0])).isEmpty();
    assertThat(map.values().toArray(Int[]::new)).isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(values = { ReferenceType.WEAK, ReferenceType.SOFT },
      population = Population.FULL, removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void values_contains(Map<Int, Int> map, CacheContext context) {
    Int value = new Int(context.original().get(context.firstKey()));
    assertThat(map.values().contains(value)).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, population = Population.FULL,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DEFAULT,
      stats = Stats.ENABLED, removalListener = Listener.DEFAULT)
  public void entrySetToArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.entrySet().toArray()).isEmpty();
    assertThat(map.entrySet().toArray(new Map.Entry<?, ?>[0])).isEmpty();
    assertThat(map.entrySet().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, population = Population.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_contains(Map<Int, Int> map, CacheContext context) {
    var entry = entry(new Int(context.firstKey()),
        new Int(context.original().get(context.firstKey())));
    assertThat(map.entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(requiresWeakOrSoft = true, population = Population.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void entrySet_contains_nullValue(Map<Int, Int> map, CacheContext context) {
    var entry = new AbstractMap.SimpleEntry<>(context.firstKey(), null);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.entrySet().contains(entry)).isFalse();
  }

  /** Ensures that that all the pending work is performed (Guava limits work per cycle). */
  private static void awaitFullCleanup(Cache<?, ?> cache) {
    int attempts = 0;
    for (;;) {
      long size = cache.estimatedSize();
      cache.cleanUp();
      attempts++;

      if (size == cache.estimatedSize()) {
        break;
      }
      assertThat(attempts).isLessThan(100);
    }
  }
}
