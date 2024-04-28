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
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.cache.testing.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.testing.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.testing.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.function.Function.identity;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.WARN;

import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.References.InternalReference;
import com.github.benmanes.caffeine.cache.References.LookupKeyEqualsReference;
import com.github.benmanes.caffeine.cache.References.LookupKeyReference;
import com.github.benmanes.caffeine.cache.References.SoftValueReference;
import com.github.benmanes.caffeine.cache.References.WeakKeyEqualsReference;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.github.benmanes.caffeine.cache.References.WeakValueReference;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExpiry;
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
import com.github.benmanes.caffeine.cache.testing.CheckMaxLogLevel;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.Int;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.google.common.testing.EqualsTester;
import com.google.common.testing.GcFinalization;

/**
 * The test cases for caches that support a reference eviction policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(WARN)
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
  @CacheSpec(population = Population.FULL,
      requiresWeakOrSoft = true, evictionListener = Listener.REJECTING)
  public void collect_evictionListener_failure(CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();

    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).evictionNotifications().hasSize(context.initialSize());

    var events = TestLoggerFactory.getLoggingEvents().stream()
        .filter(e -> e.getFormattedMessage().equals("Exception thrown by eviction listener"))
        .collect(toImmutableList());
    assertThat(events).hasSize((int) context.initialSize());
    for (var event : events) {
      assertThat(event.getThrowable().orElseThrow()).isInstanceOf(RejectedExecutionException.class);
      assertThat(event.getLevel()).isEqualTo(WARN);
    }
  }

  /* --------------- Cache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getIfPresent(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getIfPresent(key)).isNull();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void get(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context, context.original());

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.get(key, k -> context.absentValue())).isEqualTo(context.absentValue());

    assertThat(cache).whenCleanedUp().hasSize(1);
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  public void get_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    GcFinalization.awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> cache.get(key, identity()));
    assertThat(cache).doesNotContainKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getAllPresent(Cache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAllPresent(keys)).isExhaustivelyEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getAll(Cache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    List<Map.Entry<Int, Int>> collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(keys::contains)));
    if (!context.isStrongValues()) {
      for (var key : keys) {
        collected.add(new SimpleEntry<>(key, null));
      }
    }

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAll(keys, keysToLoad -> Maps.asMap(keysToLoad, Int::negate)))
        .containsExactlyEntriesIn(Maps.asMap(keys, Int::negate));

    assertThat(cache).whenCleanedUp().hasSize(keys.size());
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void put(Cache<Int, Int> cache, CacheContext context) {
    var key = intern(context.firstKey());
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<>(key, null));
    }

    context.clear();
    GcFinalization.awaitFullGc();
    cache.put(key, context.absentValue());
    assertThat(cache).whenCleanedUp().hasSize(1);

    if (context.isStrongValues()) {
      assertThat(context).evictionNotifications().withCause(COLLECTED)
          .contains(collected).exclusively();
      assertThat(context).removalNotifications().hasSize(context.initialSize());
      assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
      assertThat(context).removalNotifications().withCause(REPLACED).contains(key, key.negate());
    } else {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putAll(Cache<Int, Int> cache, CacheContext context) {
    var collected = getExpectedAfterGc(context, context.original());
    var entries = Map.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.absentKey(), context.absentValue());
    context.clear();

    GcFinalization.awaitFullGc();
    cache.putAll(entries);

    assertThat(context.cache()).whenCleanedUp().hasSize(entries.size());
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void invalidate(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = cache.getIfPresent(key);
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache).whenCleanedUp().hasSize(1);

    cache.invalidate(key);
    assertThat(cache).isEmpty();
    assertThat(value).isNotNull();

    assertThat(context).evictionNotifications().withCause(COLLECTED)
        .contains(collected).exclusively();
    assertThat(context).removalNotifications().hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
    assertThat(context).removalNotifications().withCause(EXPLICIT).contains(key, value);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void invalidateAll_iterable(Cache<Int, Int> cache, CacheContext context) {
    Map<Int, Int> retained;
    List<Map.Entry<Int, Int>> collected;
    var keys = context.firstMiddleLastKeys();
    if (context.isStrongValues()) {
      retained = Maps.toMap(context.firstMiddleLastKeys(), key -> context.original().get(key));
      collected = getExpectedAfterGc(context,
          Maps.filterKeys(context.original(), not(keys::contains)));
    } else {
      retained = Map.of();
      collected = getExpectedAfterGc(context, context.original());
    }

    context.clear();
    GcFinalization.awaitFullGc();
    cache.invalidateAll(keys);
    assertThat(cache).whenCleanedUp().isEmpty();

    if (context.isStrongValues()) {
      assertThat(context).evictionNotifications().withCause(COLLECTED)
          .contains(collected).exclusively();
      assertThat(context).removalNotifications().hasSize(context.initialSize());
      assertThat(context).removalNotifications().withCause(EXPLICIT).contains(retained);
      assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
    } else {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void invalidateAll_full(Cache<Int, Int> cache, CacheContext context) {
    Map<Int, Int> retained;
    var keys = context.firstMiddleLastKeys();
    List<Map.Entry<Int, Int>> collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(keys::contains)));
    if (context.isStrongValues()) {
      retained = Maps.toMap(context.firstMiddleLastKeys(), key -> context.original().get(key));
    } else {
      retained = Map.of();
      for (var key : keys) {
        collected.add(new SimpleEntry<>(key, null));
      }
    }

    context.clear();
    GcFinalization.awaitFullGc();
    cache.invalidateAll();

    if (context.isStrongValues()) {
      assertThat(context).evictionNotifications().withCause(COLLECTED)
          .contains(collected).exclusively();
      assertThat(context).removalNotifications().hasSize(context.initialSize());
      assertThat(context).removalNotifications().withCause(EXPLICIT).contains(retained);
      assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
    } else {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void cleanUp(CacheContext context) {
    var collected = getExpectedAfterGc(context, context.original());

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  /* --------------- LoadingCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void get_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache).whenCleanedUp().hasSize(1);
    assertThat(cache.get(key)).isEqualTo(value);

    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT}, loader = Loader.IDENTITY)
  public void get_loading_expiryFails(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    GcFinalization.awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> cache.get(key));
    assertThat(cache).doesNotContainKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING,
      loader = {Loader.NEGATIVE, Loader.BULK_NEGATIVE})
  public void getAll_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    List<Map.Entry<Int, Int>> collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(keys::contains)));
    if (!context.isStrongValues()) {
      for (var key : keys) {
        collected.add(new SimpleEntry<>(key, null));
      }
    }

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAll(keys)).containsExactlyEntriesIn(Maps.asMap(keys, Int::negate));
    assertThat(cache).whenCleanedUp().hasSize(keys.size());

    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING, loader = Loader.IDENTITY)
  public void refresh(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(context.cache()).whenCleanedUp().hasSize(1);
    assertThat(cache.refresh(key)).succeedsWith(key);
    assertThat(cache).doesNotContainEntry(key, value);

    assertThat(context).evictionNotifications().withCause(COLLECTED)
        .contains(collected).exclusively();
    assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
    assertThat(context).removalNotifications().withCause(REPLACED).contains(key, value);
    assertThat(context).removalNotifications().hasSize(context.initialSize());
  }

  /* --------------- AsyncCache --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getIfPresent_async(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(cache.synchronous()).whenCleanedUp().hasSize(1);
    assertThat(cache.synchronous().getIfPresent(key)).isEqualTo(value);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, keys = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void get_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var collected = getExpectedAfterGc(context, context.original());

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.get(context.absentKey(), identity()))
        .succeedsWith(context.absentKey());
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, keys = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void getAll_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = Set.of(context.firstKey(), context.lastKey(), context.absentKey());
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(keys::contains)));

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(cache.getAll(keys, keysToLoad -> Maps.asMap(keysToLoad, Int::negate)).join())
        .containsExactlyEntriesIn(Maps.asMap(keys, Int::negate));
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, keys = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED,  removalListener = Listener.CONSUMING)
  public void put_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var collected = getExpectedAfterGc(context, context.original());
    Int key = context.absentKey();
    context.clear();
    GcFinalization.awaitFullGc();
    cache.put(key, context.absentValue().asFuture());

    assertThat(cache).hasSize(1);
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  /* --------------- Map --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void isEmpty(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map).isNotEmpty();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void size(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map).hasSize(context.initialSize());
    assertThat(context.cache()).whenCleanedUp().isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void containsKey(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.containsKey(key)).isEqualTo(context.isStrongValues());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
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
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void clear(Map<Int, Int> map, CacheContext context) {
    var retained = Maps.toMap(context.firstMiddleLastKeys(), key -> context.original().get(key));
    var collected = getExpectedAfterGc(context, Maps.difference(
        context.original(), retained).entriesOnlyOnLeft());

    context.clear();
    GcFinalization.awaitFullGc();
    map.clear();

    assertThat(context).evictionNotifications().withCause(COLLECTED)
        .contains(collected).exclusively();
    assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
    assertThat(context).removalNotifications().withCause(EXPLICIT).contains(retained);
    assertThat(context).removalNotifications().hasSize(context.initialSize());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void putIfAbsent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<>(key, null));
    }

    context.clear();
    GcFinalization.awaitFullGc();
    Int value = map.putIfAbsent(key, context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isNotNull();
    } else {
      assertThat(value).isNull();
    }
    assertThat(context.cache()).whenCleanedUp().hasSize(1);
    assertThat(map).containsKey(key);

    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
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
    assertThat(context).hasWeightedSize(3);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  public void put_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    GcFinalization.awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> cache.put(key, key));
    assertThat(cache).doesNotContainKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void put_map(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int replaced = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<>(key, null));
    }

    context.clear();
    GcFinalization.awaitFullGc();
    Int value = map.put(key, context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isNotNull();
    } else {
      assertThat(value).isNull();
    }

    assertThat(context.cache()).whenCleanedUp().hasSize(1);
    assertThat(map).containsKey(key);

    if (context.isStrongValues()) {
      assertThat(context).evictionNotifications().withCause(COLLECTED)
          .contains(collected).exclusively();
      assertThat(context).removalNotifications().hasSize(context.initialSize());
      assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
      assertThat(context).removalNotifications().withCause(REPLACED).contains(key, replaced);
    } else {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  public void put_map_expiryFails(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    GcFinalization.awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> map.put(key, key));
    assertThat(map).doesNotContainKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
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
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void replaceConditionally(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.replace(key, value, context.absentValue())).isTrue();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void remove(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int removed = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<>(key, null));
    }

    context.clear();
    GcFinalization.awaitFullGc();
    Int value = map.remove(key);
    if (context.isStrongValues()) {
      assertThat(value).isNotNull();
    } else {
      assertThat(value).isNull();
    }
    assertThat(context.cache()).whenCleanedUp().isEmpty();

    if (context.isStrongValues()) {
      assertThat(context).evictionNotifications().withCause(COLLECTED)
          .contains(collected).exclusively();
      assertThat(context).removalNotifications().hasSize(context.initialSize());
      assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
      assertThat(context).removalNotifications().withCause(EXPLICIT).contains(key, removed);
    } else {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void removeConditionally_found(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.remove(key, value)).isTrue();
    assertThat(context.cache()).whenCleanedUp().isEmpty();

    assertThat(context).evictionNotifications().withCause(COLLECTED)
        .contains(collected).exclusively();
    assertThat(context).removalNotifications().hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
    assertThat(context).removalNotifications().withCause(EXPLICIT).contains(key, value);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void removeConditionally_notFound(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    collected.add(new SimpleEntry<>(key, null));

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.remove(key, context.absentValue())).isFalse();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void computeIfAbsent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<>(key, null));
    }

    context.clear();
    GcFinalization.awaitFullGc();
    Int value = map.computeIfAbsent(key, k -> context.absentValue());
    assertThat(context.cache()).whenCleanedUp().hasSize(1);
    if (context.isStrongValues()) {
      assertThat(value).isNotEqualTo(context.absentValue());
    } else {
      assertThat(value).isEqualTo(context.absentValue());
    }
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void computeIfAbsent_nullValue(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    collected.add(new SimpleEntry<>(key, null));

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.computeIfAbsent(key, k -> null)).isNull();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  public void computeIfAbsent_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    GcFinalization.awaitFullGc();

    cache.asMap().computeIfAbsent(key, k -> Int.listOf(1, 2, 3));
    if (context.isStrongValues()) {
      assertThat(context).hasWeightedSize(1);
    } else {
      assertThat(context).hasWeightedSize(3);
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  public void computeIfAbsent_expiryFails(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    GcFinalization.awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> map.computeIfAbsent(key, identity()));
    assertThat(map).doesNotContainKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void computeIfPresent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int replaced = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<>(key, null));
    }

    context.clear();
    GcFinalization.awaitFullGc();
    Int value = map.computeIfPresent(key, (k, v) -> context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isEqualTo(context.absentValue());
      assertThat(context.cache()).whenCleanedUp().hasSize(1);

      assertThat(context).evictionNotifications().withCause(COLLECTED)
          .contains(collected).exclusively();
      assertThat(context).removalNotifications().hasSize(context.initialSize());
      assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
      assertThat(context).removalNotifications().withCause(REPLACED).contains(key, replaced);
    } else {
      assertThat(value).isNull();
      assertThat(context.cache()).whenCleanedUp().isEmpty();
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void compute(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int replaced = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<>(key, null));
    }

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
    assertThat(context.cache()).whenCleanedUp().hasSize(1);

    if (context.isStrongValues()) {
      assertThat(context).evictionNotifications().withCause(COLLECTED)
          .contains(collected).exclusively();
      assertThat(context).removalNotifications().hasSize(context.initialSize());
      assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
      assertThat(context).removalNotifications().withCause(REPLACED).contains(key, replaced);
    } else {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void compute_nullValue(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    collected.add(new SimpleEntry<>(key, null));

    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.compute(key, (k, v) -> {
      assertThat(v).isNull();
      return null;
    })).isNull();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  public void compute_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    GcFinalization.awaitFullGc();

    cache.asMap().compute(key, (k, v) -> Int.listOf(1, 2, 3));
    assertThat(context).hasWeightedSize(3);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  public void compute_expiryFails(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    GcFinalization.awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> map.compute(key, (k, v) -> k));
    assertThat(map).doesNotContainKey(key);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  public void merge(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int replaced = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<>(key, null));
    }

    context.clear();
    GcFinalization.awaitFullGc();
    Int value = map.merge(key, context.absentValue(), (oldValue, v) -> {
      if (context.isWeakKeys() && !context.isStrongValues()) {
        Assert.fail("Should not be invoked");
      }
      return context.absentValue();
    });
    assertThat(value).isEqualTo(context.absentValue());
    assertThat(context.cache()).whenCleanedUp().hasSize(1);

    if (context.isStrongValues()) {
      assertThat(context).evictionNotifications().withCause(COLLECTED)
          .contains(collected).exclusively();
      assertThat(context).removalNotifications().hasSize(context.initialSize());
      assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
      assertThat(context).removalNotifications().withCause(REPLACED).contains(key, replaced);
    } else {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
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
    assertThat(context).hasWeightedSize(3);
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

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  public void keySet_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.keySet().toArray()).isEmpty();
    assertThat(map.keySet().toArray(new Int[0])).isEmpty();
    assertThat(map.keySet().toArray(Int[]::new)).isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, keys = ReferenceType.WEAK,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void keySet_contains(Map<Int, Int> map, CacheContext context) {
    assertThat(map.keySet().contains(new Int(context.firstKey()))).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  public void values_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.values().toArray()).isEmpty();
    assertThat(map.values().toArray(new Int[0])).isEmpty();
    assertThat(map.values().toArray(Int[]::new)).isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void values_contains(Map<Int, Int> map, CacheContext context) {
    Int value = new Int(context.original().get(context.firstKey()));
    assertThat(map.values().contains(value)).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  public void entrySet_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    GcFinalization.awaitFullGc();
    assertThat(map.entrySet().toArray()).isEmpty();
    assertThat(map.entrySet().toArray(new Map.Entry<?, ?>[0])).isEmpty();
    assertThat(map.entrySet().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void entrySet_contains(Map<Int, Int> map, CacheContext context) {
    var entry = Map.entry(new Int(context.firstKey()),
        new Int(context.original().get(context.firstKey())));
    assertThat(map.entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  public void entrySet_contains_nullValue(Map<Int, Int> map, CacheContext context) {
    var entry = new AbstractMap.SimpleEntry<>(context.firstKey(), null);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.entrySet().contains(entry)).isFalse();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true)
  public void entrySet_equals(Map<Int, Int> map, CacheContext context) {
    var expected = context.absent();
    map.putAll(expected);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.entrySet().equals(expected.entrySet())).isFalse();
    assertThat(expected.entrySet().equals(map.entrySet())).isFalse();

    assertThat(context.cache()).whenCleanedUp().hasSize(expected.size());
    assertThat(map.entrySet().equals(expected.entrySet())).isTrue();
    assertThat(expected.entrySet().equals(map.entrySet())).isTrue();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true)
  public void equals(Map<Int, Int> map, CacheContext context) {
    var expected = context.absent();
    map.putAll(expected);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.equals(expected)).isFalse();
    assertThat(expected.equals(map)).isFalse();

    assertThat(context.cache()).whenCleanedUp().hasSize(expected.size());
    assertThat(map.equals(expected)).isTrue();
    assertThat(expected.equals(map)).isTrue();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, requiresWeakOrSoft = true)
  public void equals_cleanUp(Map<Int, Int> map, CacheContext context) {
    var copy = context.original().entrySet().stream().collect(toImmutableMap(
        entry -> new Int(entry.getKey()), entry -> new Int(entry.getValue())));
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.equals(copy)).isFalse();
    assertThat(context.cache()).isEmpty();
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true)
  public void hashCode(Map<Int, Int> map, CacheContext context) {
    var expected = context.absent();
    map.putAll(expected);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(map.hashCode()).isEqualTo(expected.hashCode());

    assertThat(context.cache()).whenCleanedUp().hasSize(expected.size());
    assertThat(map.hashCode()).isEqualTo(expected.hashCode());
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true)
  public void toString(Map<Int, Int> map, CacheContext context) {
    var expected = context.absent();
    map.putAll(expected);
    context.clear();

    GcFinalization.awaitFullGc();
    assertThat(parseToString(map)).containsExactlyEntriesIn(parseToString(expected));

    assertThat(context.cache()).whenCleanedUp().hasSize(expected.size());
    assertThat(parseToString(map)).containsExactlyEntriesIn(parseToString(expected));
  }

  private static Map<String, String> parseToString(Map<Int, Int> map) {
    return Splitter.on(',').trimResults().omitEmptyStrings().withKeyValueSeparator("=")
        .split(map.toString().replaceAll("\\{|\\}", ""));
  }

  /* --------------- Reference --------------- */

  @SuppressWarnings("ClassEscapesDefinedScope")
  @Test(dataProviderClass = ReferenceTest.class, dataProvider = "references")
  public void reference(InternalReference<Int> reference,
      Int item, boolean identity, boolean isKey) {
    assertThat(reference.get()).isSameInstanceAs(item);
    if (isKey) {
      int hash = identity ? System.identityHashCode(item) : item.hashCode();
      assertThat(reference.getKeyReference()).isSameInstanceAs(reference);
      assertThat(reference.toString()).contains("key=" + item);
      assertThat(reference.hashCode()).isEqualTo(hash);
    } else {
      assertThat(reference.getKeyReference()).isSameInstanceAs(item);
      assertThat(reference.toString()).contains("value=" + item);
    }
  }

  @Test
  public void reference_equality() {
    var first = new Int(1);
    var second = new Int(1);
    new EqualsTester()
        .addEqualityGroup(new LookupKeyReference<>(first), new WeakKeyReference<>(first, null))
        .addEqualityGroup(new LookupKeyReference<>(second), new WeakKeyReference<>(second, null))
        .testEquals();
    new EqualsTester()
        .addEqualityGroup(
            new LookupKeyEqualsReference<>(first), new WeakKeyEqualsReference<>(first, null),
            new LookupKeyEqualsReference<>(second), new WeakKeyEqualsReference<>(second, null))
        .testEquals();
    new EqualsTester()
        .addEqualityGroup(new WeakValueReference<>(first, first, null),
            new SoftValueReference<>(first, first, null))
        .addEqualityGroup(new WeakValueReference<>(second, second, null),
            new SoftValueReference<>(second, second, null))
        .testEquals();
  }

  @DataProvider(name = "references")
  public Object[][] providesReferences() {
    var item = new Int(1);
    return new Object[][] {
      new Object[] {new LookupKeyReference<>(item), item, true, true},
      new Object[] {new WeakKeyReference<>(item, null), item, true, true},
      new Object[] {new LookupKeyEqualsReference<>(item), item, false, true},
      new Object[] {new WeakKeyEqualsReference<>(item, null), item, false, true},
      new Object[] {new WeakValueReference<>(item, item, null), item, true, false},
      new Object[] {new SoftValueReference<>(item, item, null), item, true, false},
    };
  }

  private List<Map.Entry<Int, Int>> getExpectedAfterGc(
      CacheContext context, Map<Int, Int> original) {
    var expected = new ArrayList<Map.Entry<Int, Int>>();
    original.forEach((key, value) -> {
      key = context.isStrongKeys() ? new Int(key) : null;
      value = context.isStrongValues() ? new Int(value) : null;
      expected.add(new SimpleEntry<>(key, value));
    });
    return expected;
  }
}
