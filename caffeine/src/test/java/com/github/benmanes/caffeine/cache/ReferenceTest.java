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

import static com.github.benmanes.caffeine.cache.AsyncCacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheContext.intern;
import static com.github.benmanes.caffeine.cache.CacheContextSubject.assertThat;
import static com.github.benmanes.caffeine.cache.CacheSubject.assertThat;
import static com.github.benmanes.caffeine.cache.RemovalCause.COLLECTED;
import static com.github.benmanes.caffeine.cache.RemovalCause.EXPLICIT;
import static com.github.benmanes.caffeine.cache.RemovalCause.REPLACED;
import static com.github.benmanes.caffeine.testing.Awaits.awaitFullGc;
import static com.github.benmanes.caffeine.testing.CollectionSubject.assertThat;
import static com.github.benmanes.caffeine.testing.FutureSubject.assertThat;
import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.github.benmanes.caffeine.testing.MapSubject.assertThat;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.util.AbstractMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.benmanes.caffeine.cache.CacheSpec.CacheExpiry;
import com.github.benmanes.caffeine.cache.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.CacheSpec.Stats;
import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.References.InternalReference;
import com.github.benmanes.caffeine.cache.References.LookupKeyEqualsReference;
import com.github.benmanes.caffeine.cache.References.LookupKeyReference;
import com.github.benmanes.caffeine.cache.References.SoftValueReference;
import com.github.benmanes.caffeine.cache.References.WeakKeyEqualsReference;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.github.benmanes.caffeine.cache.References.WeakValueReference;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.google.common.testing.EqualsTester;
import com.google.errorprone.annotations.Var;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The test cases for caches that support a reference eviction policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(TRACE)
final class ReferenceTest {

  // These tests require that the JVM uses a garbage collection algorithm that can immediately
  // discard all unreachable references so that soft references can be reliably garbage collected
  // by making them behave like weak references. Typically, this combination works:
  // -XX:SoftRefLRUPolicyMSPerMB=0 -XX:+UseParallelGC -XX:+ParallelRefProcEnabled

  @ParameterizedTest
  @CacheSpec(keys = ReferenceType.WEAK, population = Population.FULL)
  void identity_keys(Cache<Int, Int> cache, CacheContext context) {
    var key = new Int(context.firstKey());
    assertThat(cache).doesNotContainKey(key);
  }

  @ParameterizedTest
  @CacheSpec(values = {ReferenceType.WEAK, ReferenceType.SOFT}, population = Population.FULL)
  void identity_values(Cache<Int, Int> cache, CacheContext context) {
    var value = new Int(context.original().get(context.firstKey()));
    assertThat(cache).doesNotContainKey(value);
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL,
      requiresWeakOrSoft = true, evictionListener = Listener.REJECTING)
  void collect_evictionListener_failure(CacheContext context) {
    context.clear();
    awaitFullGc();

    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).evictionNotifications().hasSize(context.initialSize());
    assertThat(logEvents()
        .withMessage("Exception thrown by eviction listener")
        .withThrowable(RejectedExecutionException.class)
        .withLevel(WARN)
        .exclusively())
        .hasSize(context.initialSize());
  }

  /* --------------- Cache --------------- */

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void getIfPresent(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(cache.getIfPresent(key)).isNull();
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void get(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context, context.original());

    context.clear();
    awaitFullGc();
    assertThat(cache.get(key, k -> context.absentValue())).isEqualTo(context.absentValue());

    assertThat(cache).whenCleanedUp().hasSize(1);
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  void get_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> cache.get(key, identity()));
    assertThat(cache).doesNotContainKey(key);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void getAllPresent(Cache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(cache.getAllPresent(keys)).isExhaustivelyEmpty();
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void getAll(Cache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    List<Map.Entry<Int, Int>> collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(keys::contains)));
    if (!context.isStrongValues()) {
      for (var key : keys) {
        collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
      }
    }

    context.clear();
    awaitFullGc();
    assertThat(cache.getAll(keys, keysToLoad -> Maps.toMap(keysToLoad, Int::negate)))
        .containsExactlyEntriesIn(Maps.toMap(keys, Int::negate));

    assertThat(cache).whenCleanedUp().hasSize(keys.size());
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void put(Cache<Int, Int> cache, CacheContext context) {
    var key = intern(context.firstKey());
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void putAll(Cache<Int, Int> cache, CacheContext context) {
    var collected = getExpectedAfterGc(context, context.original());
    var entries = Map.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.absentKey(), context.absentValue());
    context.clear();

    awaitFullGc();
    cache.putAll(entries);

    assertThat(context.cache()).whenCleanedUp().hasSize(entries.size());
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void invalidate(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = cache.getIfPresent(key);
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CacheSpec(population = Population.FULL,
      keys = ReferenceType.STRONG, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void invalidateAll_iterable(Cache<Int, Int> cache, CacheContext context) {
    Map<Int, Int> retained;
    List<Map.Entry<Int, Int>> collected;
    var keys = context.firstMiddleLastKeys();
    if (context.isStrongValues()) {
      retained = Maps.toMap(context.firstMiddleLastKeys(),
          key -> requireNonNull(context.original().get(key)));
      collected = getExpectedAfterGc(context,
          Maps.filterKeys(context.original(), not(keys::contains)));
    } else {
      retained = Map.of();
      collected = getExpectedAfterGc(context, context.original());
    }

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void invalidateAll_full(Cache<Int, Int> cache, CacheContext context) {
    Map<Int, Int> retained;
    var keys = context.firstMiddleLastKeys();
    List<Map.Entry<Int, Int>> collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(keys::contains)));
    if (context.isStrongValues()) {
      retained = Maps.toMap(context.firstMiddleLastKeys(),
          key -> requireNonNull(context.original().get(key)));
    } else {
      retained = Map.of();
      for (var key : keys) {
        collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
      }
    }

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void cleanUp(CacheContext context) {
    var collected = getExpectedAfterGc(context, context.original());

    context.clear();
    awaitFullGc();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true, maximumSize = Maximum.FULL)
  void coldest(CacheContext context, Eviction<Int, Int> eviction) {
    context.clear();
    var coldest = eviction.coldest(stream -> {
      awaitFullGc();
      return stream.collect(toImmutableList());
    });
    assertThat(coldest).isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true, maximumSize = Maximum.FULL)
  void hottest(CacheContext context, Eviction<Int, Int> eviction) {
    context.clear();
    var hottest = eviction.hottest(stream -> {
      awaitFullGc();
      return stream.collect(toImmutableList());
    });
    assertThat(hottest).isEmpty();
  }

  /* --------------- LoadingCache --------------- */

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void get_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    awaitFullGc();
    assertThat(cache).whenCleanedUp().hasSize(1);
    assertThat(cache.get(key)).isEqualTo(value);

    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT}, loader = Loader.IDENTITY)
  void get_loading_expiryFails(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> cache.get(key));
    assertThat(cache).doesNotContainKey(key);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING,
      loader = {Loader.NEGATIVE, Loader.BULK_NEGATIVE})
  void getAll_loading(LoadingCache<Int, Int> cache, CacheContext context) {
    var keys = context.firstMiddleLastKeys();
    List<Map.Entry<Int, Int>> collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(keys::contains)));
    if (!context.isStrongValues()) {
      for (var key : keys) {
        collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
      }
    }

    context.clear();
    awaitFullGc();
    assertThat(cache.getAll(keys)).containsExactlyEntriesIn(Maps.toMap(keys, Int::negate));
    assertThat(cache).whenCleanedUp().hasSize(keys.size());

    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING, loader = Loader.IDENTITY)
  void refresh(LoadingCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void getIfPresent_async(AsyncCache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    context.clear();

    awaitFullGc();
    assertThat(cache.synchronous()).whenCleanedUp().hasSize(1);
    assertThat(cache.synchronous().getIfPresent(key)).isEqualTo(value);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, keys = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void get_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var collected = getExpectedAfterGc(context, context.original());

    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(cache.get(context.absentKey(), identity()))
          .succeedsWith(context.absentKey());
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, keys = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void getAll_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var keys = Set.of(context.firstKey(), context.lastKey(), context.absentKey());
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(keys::contains)));

    context.clear();
    awaitFullGc();
    assertThat(cache.getAll(keys, keysToLoad -> Maps.toMap(keysToLoad, Int::negate)).join())
        .containsExactlyEntriesIn(Maps.toMap(keys, Int::negate));
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, keys = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED,  removalListener = Listener.CONSUMING)
  void put_async(AsyncCache<Int, Int> cache, CacheContext context) {
    var collected = getExpectedAfterGc(context, context.original());
    Int key = context.absentKey();
    context.clear();
    awaitFullGc();
    cache.put(key, context.absentValue().toFuture());

    assertThat(cache).hasSize(1);
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  /* --------------- Map --------------- */

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void isEmpty(Map<Int, Int> map, CacheContext context) {
    context.clear();
    awaitFullGc();
    assertThat(map).isNotEmpty();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void size(Map<Int, Int> map, CacheContext context) {
    context.clear();
    awaitFullGc();
    assertThat(map).hasSize(context.initialSize());
    assertThat(context.cache()).whenCleanedUp().isEmpty();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void containsKey(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(map.containsKey(key)).isEqualTo(context.isStrongValues());
    });
  }

  @ParameterizedTest
  @SuppressFBWarnings("DLS_DEAD_LOCAL_STORE_OF_NULL")
  @SuppressWarnings({"PMD.UnusedAssignment", "UnusedAssignment"})
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void containsValue(Map<Int, Int> map, CacheContext context) {
    @Var var key = context.firstKey();
    Int value = context.original().get(key);
    context.clear();
    awaitFullGc();
    assertThat(map.containsValue(value)).isTrue();

    key = null;
    retry(() -> {
      awaitFullGc();
      assertThat(map.containsValue(value)).isNotEqualTo(context.isWeakKeys());
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void clear(Map<Int, Int> map, CacheContext context) {
    var retained = Maps.toMap(context.firstMiddleLastKeys(),
        key -> requireNonNull(context.original().get(key)));
    var collected = getExpectedAfterGc(context, Maps.difference(
        context.original(), retained).entriesOnlyOnLeft());

    context.clear();
    awaitFullGc();
    map.clear();

    assertThat(context).evictionNotifications().withCause(COLLECTED)
        .contains(collected).exclusively();
    assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
    assertThat(context).removalNotifications().withCause(EXPLICIT).contains(retained);
    assertThat(context).removalNotifications().hasSize(context.initialSize());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void putIfAbsent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void put_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    awaitFullGc();

    var value = cache.asMap().put(key, Int.listOf(1, 2, 3));
    if (context.isStrongValues()) {
      assertThat(value).isEqualTo(Int.listOf(1));
    } else {
      assertThat(value).isNull();
    }
    assertThat(context).hasWeightedSize(3);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  void put_expiryFails(Cache<Int, Int> cache, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> cache.put(key, key));
    assertThat(cache).doesNotContainKey(key);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void put_map(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var replaced = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  void put_map_expiryFails(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> map.put(key, key));
    assertThat(map).doesNotContainKey(key);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void replace(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();

    awaitFullGc();
    Int value = map.replace(key, context.absentValue());
    if (context.isStrongValues()) {
      assertThat(value).isNotNull();
    } else {
      assertThat(value).isNull();
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void replaceConditionally_found(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);

    context.clear();
    awaitFullGc();
    assertThat(map.replace(key, value, context.absentValue())).isTrue();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, values = ReferenceType.WEAK,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void replaceConditionally_collectedValue(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    context.clear();
    awaitFullGc();
    assertThat(map.replace(key, context.absentValue(), context.absentValue())).isFalse();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void remove(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var removed = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void removeConditionally_found(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    Int value = context.original().get(key);
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    awaitFullGc();
    assertThat(map.remove(key, value)).isTrue();
    assertThat(context.cache()).whenCleanedUp().isEmpty();

    assertThat(context).evictionNotifications().withCause(COLLECTED)
        .contains(collected).exclusively();
    assertThat(context).removalNotifications().hasSize(context.initialSize());
    assertThat(context).removalNotifications().withCause(COLLECTED).contains(collected);
    assertThat(context).removalNotifications().withCause(EXPLICIT).contains(key, value);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void removeConditionally_notFound(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));

    context.clear();
    awaitFullGc();
    assertThat(map.remove(key, context.absentValue())).isFalse();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    assertThat(context).notifications().withCause(COLLECTED)
        .contains(collected).exclusively();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void computeIfAbsent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void computeIfAbsent_nullValue(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isGuava()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
    assertThat(map.computeIfAbsent(key, k -> null)).isNull();
    assertThat(context.cache()).whenCleanedUp().isEmpty();
    if (context.isGuava()) {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected);
    } else {
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void computeIfAbsent_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    awaitFullGc();

    cache.asMap().computeIfAbsent(key, k -> Int.listOf(1, 2, 3));
    if (context.isStrongValues()) {
      assertThat(context).hasWeightedSize(1);
    } else {
      assertThat(context).hasWeightedSize(3);
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  void computeIfAbsent_expiryFails(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> map.computeIfAbsent(key, identity()));
    assertThat(map).doesNotContainKey(key);
  }

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void computeIfPresent(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var replaced = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues() && !context.isGuava()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
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
      if (context.isGuava()) {
        // when a null valued EXPLICIT notification then that is rejected by the listener
        assertThat(context).notifications().withCause(COLLECTED).contains(collected);
        assertThat(logEvents()
            .withMessage("Exception thrown by removal listener")
            .withThrowable(NullPointerException.class)
            .withLevel(WARN)
            .exclusively())
            .hasSizeLessThan(2);
      } else {
        assertThat(context).notifications().withCause(COLLECTED)
            .contains(collected).exclusively();
      }
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void compute(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var replaced = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
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

  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void compute_nullValue(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));

    context.clear();
    awaitFullGc();
    assertThat(map.compute(key, (k, v) -> {
      assertThat(v).isNull();
      return null;
    })).isNull();
    assertThat(context.cache()).whenCleanedUp().isEmpty();

    if (context.isGuava()) {
      // when a null valued EXPLICIT notification then that is rejected by the listener
      assertThat(context).notifications().withCause(COLLECTED).contains(collected);
      assertThat(logEvents()
          .withMessage("Exception thrown by removal listener")
          .withThrowable(NullPointerException.class)
          .withLevel(WARN)
          .exclusively())
          .hasSizeLessThan(2);
    } else {
      assertThat(logEvents()).isEmpty();
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
      assertThat(context).notifications().withCause(COLLECTED)
          .contains(collected).exclusively();
    }
  }

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void compute_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    awaitFullGc();

    cache.asMap().compute(key, (k, v) -> Int.listOf(1, 2, 3));
    assertThat(context).hasWeightedSize(3);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, expiry = CacheExpiry.MOCKITO,
      values = {ReferenceType.WEAK, ReferenceType.SOFT})
  void compute_expiryFails(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();

    context.clear();
    awaitFullGc();
    when(context.expiry().expireAfterCreate(any(), any(), anyLong()))
        .thenThrow(IllegalStateException.class);
    assertThrows(IllegalStateException.class, () -> map.compute(key, (k, v) -> k));
    assertThat(map).doesNotContainKey(key);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.DISABLED, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.CONSUMING)
  void merge(Map<Int, Int> map, CacheContext context) {
    Int key = context.firstKey();
    var replaced = new Int(context.original().get(key));
    var collected = getExpectedAfterGc(context,
        Maps.filterKeys(context.original(), not(equalTo(key))));
    if (!context.isStrongValues()) {
      collected.add(new SimpleEntry<Int, @Nullable Int>(key, null));
    }

    context.clear();
    awaitFullGc();
    Int value = map.merge(key, context.absentValue(), (oldValue, v) -> {
      if (context.isWeakKeys() && !context.isStrongValues()) {
        Assertions.fail("Should not be invoked");
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

  @ParameterizedTest
  @CacheSpec(population = Population.EMPTY, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.COLLECTION,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void merge_weighted(Cache<Int, List<Int>> cache, CacheContext context) {
    Int key = context.absentKey();
    cache.put(key, Int.listOf(1));
    awaitFullGc();

    cache.asMap().merge(key, Int.listOf(1, 2, 3), (oldValue, v) -> {
      if (context.isWeakKeys() && !context.isStrongValues()) {
        Assertions.fail("Should never be called");
      }
      return v;
    });
    assertThat(context).hasWeightedSize(3);
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void iterators(Map<Int, Int> map, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(map.keySet().iterator().hasNext()).isFalse();
      assertThat(map.values().iterator().hasNext()).isFalse();
      assertThat(map.entrySet().iterator().hasNext()).isFalse();
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void keySet_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(map.keySet().toArray()).isEmpty();
      assertThat(map.keySet().toArray(new Int[0])).isEmpty();
      assertThat(map.keySet().toArray(Int[]::new)).isEmpty();
    });
  }

  @CheckNoStats
  @ParameterizedTest
  @SuppressFBWarnings("MUI_USE_CONTAINSKEY")
  @SuppressWarnings("RedundantCollectionOperation")
  @CacheSpec(population = Population.FULL, keys = ReferenceType.WEAK,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  void keySet_contains(Map<Int, Int> map, CacheContext context) {
    assertThat(map.keySet().contains(new Int(context.firstKey()))).isFalse();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void keySpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    context.clear();
    awaitFullGc();
    map.keySet().spliterator().forEachRemaining(key -> Assertions.fail());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void keySpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    context.clear();
    awaitFullGc();
    assertThat(map.keySet().spliterator().tryAdvance(key -> Assertions.fail())).isFalse();
  }

  @ParameterizedTest
  @SuppressWarnings("SimplifyStreamApiCallChains")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void keyStream_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(map.keySet().stream().toArray()).isEmpty();
      assertThat(map.keySet().stream().toArray(Int[]::new)).isEmpty();
    });
  }

  @ParameterizedTest
  @SuppressWarnings("SimplifyStreamApiCallChains")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void keyStream_toArray_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(cache.asMap().keySet().stream().toArray()).isEmpty();
      assertThat(cache.asMap().keySet().stream().toArray(Int[]::new)).isEmpty();
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void values_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(map.values().toArray()).isEmpty();
      assertThat(map.values().toArray(new Int[0])).isEmpty();
      assertThat(map.values().toArray(Int[]::new)).isEmpty();
    });
  }

  @CheckNoStats
  @ParameterizedTest
  @SuppressWarnings("RedundantCollectionOperation")
  @CacheSpec(population = Population.FULL, values = {ReferenceType.WEAK, ReferenceType.SOFT},
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  void values_contains(Map<Int, Int> map, CacheContext context) {
    var value = new Int(context.original().get(context.firstKey()));
    assertThat(map.values().contains(value)).isFalse();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void valueSpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    context.clear();
    awaitFullGc();
    map.values().spliterator().forEachRemaining(value -> Assertions.fail());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void valueSpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    context.clear();
    awaitFullGc();
    assertThat(map.values().spliterator().tryAdvance(value -> Assertions.fail())).isFalse();
  }

  @ParameterizedTest
  @SuppressWarnings("SimplifyStreamApiCallChains")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void valueStream_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(map.values().stream().toArray()).isEmpty();
      assertThat(map.values().stream().toArray(Int[]::new)).isEmpty();
    });
  }

  @ParameterizedTest
  @SuppressWarnings("SimplifyStreamApiCallChains")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void valueStream_toArray_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(cache.asMap().values().stream().toArray()).isEmpty();
      assertThat(cache.asMap().values().stream().toArray(CompletableFuture<?>[]::new)).isEmpty();
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void entrySet_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(map.entrySet().toArray()).isEmpty();
      assertThat(map.entrySet().toArray(new Map.Entry<?, ?>[0])).isEmpty();
      assertThat(map.entrySet().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
    });
  }

  @CheckNoStats
  @ParameterizedTest
  @SuppressFBWarnings("MUI_USE_CONTAINSKEY")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  void entrySet_contains(Map<Int, Int> map, CacheContext context) {
    var entry = Map.entry(new Int(context.firstKey()),
        new Int(context.original().get(context.firstKey())));
    assertThat(map.entrySet().contains(entry)).isFalse();
  }

  @CheckNoStats
  @ParameterizedTest
  @CheckMaxLogLevel(WARN)
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      removalListener = {Listener.DISABLED, Listener.REJECTING})
  void entrySet_contains_nullValue(Map<Int, Int> map, CacheContext context) {
    var entry = new AbstractMap.SimpleEntry<>(context.firstKey(), null);
    context.clear();

    retry(new Runnable() {
      @SuppressFBWarnings("MUI_USE_CONTAINSKEY")
      @Override public void run() {
        awaitFullGc();
        assertThat(map.entrySet().contains(entry)).isFalse();
      }
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void entrySet_equals(Map<Int, Int> map, CacheContext context) {
    var expected = context.absent();
    map.putAll(expected);
    context.clear();

    awaitFullGc();
    assertThat(map.entrySet().equals(expected.entrySet())).isFalse();
    assertThat(expected.entrySet().equals(map.entrySet())).isFalse();

    assertThat(context.cache()).whenCleanedUp().hasSize(expected.size());
    assertThat(map.entrySet().equals(expected.entrySet())).isTrue();
    assertThat(expected.entrySet().equals(map.entrySet())).isTrue();
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void entrySpliterator_forEachRemaining(Map<Int, Int> map, CacheContext context) {
    context.clear();
    awaitFullGc();
    map.entrySet().spliterator().forEachRemaining(entry -> Assertions.fail());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void entrySpliterator_tryAdvance(Map<Int, Int> map, CacheContext context) {
    context.clear();
    awaitFullGc();
    assertThat(map.entrySet().spliterator().tryAdvance(entry -> Assertions.fail())).isFalse();
  }

  @ParameterizedTest
  @SuppressWarnings("SimplifyStreamApiCallChains")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void entryStream_toArray(Map<Int, Int> map, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(map.entrySet().stream().toArray()).isEmpty();
      assertThat(map.entrySet().stream().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
    });
  }

  @ParameterizedTest
  @SuppressWarnings("SimplifyStreamApiCallChains")
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void entryStream_toArray_async(AsyncCache<Int, Int> cache, CacheContext context) {
    context.clear();
    retry(() -> {
      awaitFullGc();
      assertThat(cache.asMap().entrySet().stream().toArray()).isEmpty();
      assertThat(cache.asMap().entrySet().stream().toArray(Map.Entry<?, ?>[]::new)).isEmpty();
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void equals(Map<Int, Int> map, CacheContext context) {
    var expected = context.absent();
    map.putAll(expected);
    context.clear();

    awaitFullGc();
    assertThat(map.equals(expected)).isFalse();
    assertThat(expected.equals(map)).isFalse();

    assertThat(context.cache()).whenCleanedUp().hasSize(expected.size());
    assertThat(map.equals(expected)).isTrue();
    assertThat(expected.equals(map)).isTrue();
  }

  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void equals_cleanUp(Map<Int, Int> map, CacheContext context) {
    var copy = context.original().entrySet().stream().collect(toImmutableMap(
        entry -> new Int(entry.getKey()), entry -> new Int(entry.getValue())));
    context.clear();

    retry(() -> {
      awaitFullGc();
      assertThat(map.equals(copy)).isFalse();
      assertThat(context.cache()).isEmpty();
    });
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void hashCode(Map<Int, Int> map, CacheContext context) {
    var expected = context.absent();
    map.putAll(expected);
    context.clear();

    awaitFullGc();
    assertThat(map.hashCode()).isEqualTo(expected.hashCode());

    assertThat(context.cache()).whenCleanedUp().hasSize(expected.size());
    assertThat(map.hashCode()).isEqualTo(expected.hashCode());
  }

  @ParameterizedTest
  @CacheSpec(population = Population.FULL, requiresWeakOrSoft = true,
      expireAfterAccess = Expire.DISABLED, expireAfterWrite = Expire.DISABLED,
      maximumSize = Maximum.UNREACHABLE, weigher = CacheWeigher.DISABLED,
      stats = Stats.ENABLED, removalListener = Listener.DISABLED)
  void toString(Map<Int, Int> map, CacheContext context) {
    var expected = context.absent();
    map.putAll(expected);
    context.clear();

    awaitFullGc();
    assertThat(parseToString(map)).containsExactlyEntriesIn(parseToString(expected));

    assertThat(context.cache()).whenCleanedUp().hasSize(expected.size());
    assertThat(parseToString(map)).containsExactlyEntriesIn(parseToString(expected));
  }

  private static Map<String, String> parseToString(Map<Int, Int> map) {
    return Splitter.on(',').trimResults().omitEmptyStrings().withKeyValueSeparator("=")
        .split(map.toString().replaceAll("[{}]", ""));
  }

  /* --------------- Reference --------------- */

  @SuppressWarnings("ClassEscapesDefinedScope")
  @ParameterizedTest @MethodSource("references")
  void reference(InternalReference<Int> reference,
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
  void reference_equality() {
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
        .addEqualityGroup(new WeakValueReference<>(first, null, null),
            new SoftValueReference<>(first, null, null))
        .testEquals();
  }

  public static Stream<Arguments> references() {
    var item = new Int(1);
    return Stream.of(
        Arguments.arguments(new LookupKeyReference<>(item), item, true, true),
        Arguments.arguments(new WeakKeyReference<>(item, null), item, true, true),
        Arguments.arguments(new LookupKeyEqualsReference<>(item), item, false, true),
        Arguments.arguments(new WeakKeyEqualsReference<>(item, null), item, false, true),
        Arguments.arguments(new WeakValueReference<>(item, item, null), item, true, false),
        Arguments.arguments(new SoftValueReference<>(item, item, null), item, true, false));
  }

  private static void retry(Runnable task) {
    for (int i = 0; i < 2; i++) {
      try {
        task.run();
        return;
      } catch (AssertionError expected) { /* ignored */ }
    }
    task.run();
  }

  @SuppressWarnings("MapEntry")
  private static List<Map.Entry<Int, Int>> getExpectedAfterGc(
      CacheContext context, Map<Int, Int> original) {
    var expected = new ArrayList<Map.Entry<Int, Int>>();
    original.forEach((@Var var key, @Var var value) -> {
      key = context.isStrongKeys() ? new Int(key) : null;
      value = context.isStrongValues() ? new Int(value) : null;
      expected.add(new SimpleImmutableEntry<@Nullable Int, @Nullable Int>(key, value));
    });
    return expected;
  }
}
