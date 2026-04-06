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

import static com.github.benmanes.caffeine.testing.LoggingEvents.logEvents;
import static com.github.benmanes.caffeine.testing.Nullness.nullRef;
import static com.github.benmanes.caffeine.testing.Nullness.nullString;
import static com.github.benmanes.caffeine.testing.Nullness.nullSupplier;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.Mockito;

import com.github.benmanes.caffeine.cache.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import com.google.common.testing.FakeTicker;
import com.google.common.testing.NullPointerTester;

/**
 * A test for the builder methods.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@CheckMaxLogLevel(TRACE)
final class CaffeineTest {
  private static final Expiry<Object, Object> expiry = Expiry.accessing(
      (key, value) -> { throw new AssertionError(); });
  private static final CacheLoader<Object, Object> loader =
      key -> { throw new AssertionError(); };

  @AfterEach
  void reset() {
    TestLoggerFactory.clear();
  }

  @Test
  void nullParameters() {
    var npeTester = new NullPointerTester();
    npeTester.testAllPublicInstanceMethods(Caffeine.newBuilder());
  }

  @Test
  void unconfigured() {
    assertThat(Caffeine.newBuilder().build()).isNotNull();
    assertThat(Caffeine.newBuilder().build(loader)).isNotNull();
    assertThat(Caffeine.newBuilder().buildAsync()).isNotNull();
    assertThat(Caffeine.newBuilder().buildAsync(loader)).isNotNull();
    assertThat(Caffeine.newBuilder().toString()).isEqualTo(Caffeine.newBuilder().toString());
  }

  @Test
  void configured() {
    var configured = Caffeine.newBuilder()
        .initialCapacity(1).weakKeys()
        .expireAfterAccess(Duration.ofSeconds(1))
        .expireAfterWrite(Duration.ofSeconds(1))
        .removalListener((k, v, c) -> {}).recordStats();
    assertThat(configured.build()).isNotNull();
    assertThat(configured.buildAsync()).isNotNull();
    assertThat(configured.build(loader)).isNotNull();
    assertThat(configured.buildAsync(loader)).isNotNull();

    assertThat(configured.refreshAfterWrite(Duration.ofSeconds(1)).toString())
        .isNotEqualTo(Caffeine.newBuilder().toString());
    assertThat(Caffeine.newBuilder().maximumSize(1).toString())
        .isNotEqualTo(Caffeine.newBuilder().maximumWeight(1).toString());
  }

  @Test
  void fromSpec_null() {
    CaffeineSpec spec = nullRef();
    assertThrows(NullPointerException.class, () -> Caffeine.from(spec));
  }

  @Test
  @CheckMaxLogLevel(WARN)
  void fromSpec_lenientParsing() {
    var cache = Caffeine.from(CaffeineSpec.parse("maximumSize=100")).weigher((k, v) -> 0).build();
    assertThat(cache).isNotNull();
    assertThat(logEvents()
        .withMessage("ignoring weigher specified without maximumWeight")
        .withoutThrowable()
        .withLevel(WARN)
        .exclusively())
        .hasSize(1);
  }

  @Test
  void fromSpec() {
    assertThat(Caffeine.from(CaffeineSpec.parse(""))).isNotNull();
  }

  @Test
  void fromString_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.from(nullString()));
  }

  @Test
  @CheckMaxLogLevel(WARN)
  void fromString_lenientParsing() {
    var cache = Caffeine.from("maximumSize=100").weigher((k, v) -> 0).build();
    assertThat(cache).isNotNull();
  }

  @Test
  void fromString() {
    assertThat(Caffeine.from("")).isNotNull();
  }

  @ParameterizedTest
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL}, compute = Compute.SYNC)
  void string(CacheContext context) {
    var description = context.caffeine().toString();
    if (context.initialCapacity() != InitialCapacity.DEFAULT) {
      assertThat(description).contains("initialCapacity=" + context.initialCapacity().size());
    }
    if (context.maximum() != Maximum.DISABLED) {
      String key = context.isWeighted() ? "maximumWeight" : "maximumSize";
      assertThat(description).contains(key + "=" + context.maximumWeightOrSize());
    }
    if (context.expireAfterWrite() != Expire.DISABLED) {
      assertThat(description).contains(
          "expireAfterWrite=" + context.expireAfterWrite().timeNanos() + "ns");
    }
    if (context.expireAfterAccess() != Expire.DISABLED) {
      assertThat(description).contains(
          "expireAfterAccess=" + context.expireAfterAccess().timeNanos() + "ns");
    }
    if (context.expiresVariably()) {
      assertThat(description).contains("expiry");
    }
    if (context.refreshAfterWrite() != Expire.DISABLED) {
      assertThat(description).contains(
          "refreshAfterWrite=" + context.refreshAfterWrite().timeNanos() + "ns");
    }
    if (context.isWeakKeys()) {
      assertThat(description).contains("keyStrength=weak");
    }
    if (context.isWeakValues()) {
      assertThat(description).contains("valueStrength=weak");
    }
    if (context.isSoftValues()) {
      assertThat(description).contains("valueStrength=soft");
    }
    if (context.evictionListenerType() != Listener.DISABLED) {
      assertThat(description).contains("evictionListener");
    }
    if (context.removalListenerType() != Listener.DISABLED) {
      assertThat(description).contains("removalListener");
    }
  }

  @Test
  void calculateHashMapCapacity() {
    @SuppressWarnings("UnnecessaryMethodReference")
    Iterable<Integer> iterable = List.of(1, 2, 3)::iterator;
    assertThat(Caffeine.calculateHashMapCapacity(iterable)).isEqualTo(16);
    assertThat(Caffeine.calculateHashMapCapacity(List.of(1, 2, 3))).isEqualTo(4);
  }

  @Test
  void hasMethodOverride_absent() {
    var overridden = Caffeine.hasMethodOverride(CacheLoader.class, loader, "loadAll", Set.class);
    assertThat(overridden).isFalse();
  }

  @Test
  @CheckMaxLogLevel(WARN)
  void hasMethodOverride_notFound() {
    var overridden = Caffeine.hasMethodOverride(CacheLoader.class, loader, "abc_xyz", Set.class);
    assertThat(overridden).isFalse();
  }

  @Test
  void hasMethodOverride_present() {
    var cacheLoader = new CacheLoader<>() {
      @Override public Object load(Object key) {
        throw new AssertionError();
      }
      @Override public Map<Object, Object> loadAll(Set<? extends Object> keys) {
        throw new AssertionError();
      }
    };
    var overridden = Caffeine.hasMethodOverride(
        CacheLoader.class, cacheLoader, "loadAll", Set.class);
    assertThat(overridden).isTrue();
  }

  /* --------------- loading --------------- */

  @Test
  void loading_nullLoader() {
    CacheLoader<Object, Object> nullLoader = nullRef();
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().build(nullLoader));
  }

  /* --------------- async --------------- */

  @Test
  void async_weakValues() {
    var builder = Caffeine.newBuilder().weakValues();
    assertThrows(IllegalStateException.class, builder::buildAsync);
  }

  @Test
  void async_softValues() {
    var builder = Caffeine.newBuilder().softValues();
    assertThrows(IllegalStateException.class, builder::buildAsync);
  }

  @Test
  void async_weakKeys_evictionListener() {
    RemovalListener<Object, Object> evictionListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().weakKeys().evictionListener(evictionListener);
    assertThrows(IllegalStateException.class, builder::buildAsync);
  }

  /* --------------- async loader --------------- */

  @Test
  void asyncLoader_nullLoader() {
    CacheLoader<Object, Object> cacheLoader = nullRef();
    AsyncCacheLoader<Object, Object> asyncLoader = nullRef();
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().buildAsync(cacheLoader));
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().buildAsync(asyncLoader));
  }

  @Test
  void asyncLoader() {
    @SuppressWarnings("UnnecessaryMethodReference")
    AsyncCacheLoader<Object, Object> asyncLoader = loader::asyncLoad;
    var cache = Caffeine.newBuilder().buildAsync(asyncLoader);
    assertThat(cache).isNotNull();
  }

  @Test
  void asyncLoader_weakValues() {
    var builder = Caffeine.newBuilder().weakValues();
    assertThrows(IllegalStateException.class, () -> builder.buildAsync(loader));
  }

  @Test
  void asyncLoader_softValues() {
    var builder = Caffeine.newBuilder().softValues();
    assertThrows(IllegalStateException.class, () -> builder.buildAsync(loader));
  }

  @Test
  void async_asyncLoader_weakKeys_evictionListener() {
    RemovalListener<Object, Object> evictionListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().weakKeys().evictionListener(evictionListener);
    assertThrows(IllegalStateException.class, () -> builder.buildAsync(loader));
  }

  /* --------------- initialCapacity --------------- */

  @Test
  void initialCapacity_negative() {
    assertThrows(IllegalArgumentException.class, () -> Caffeine.newBuilder().initialCapacity(-1));
  }

  @Test
  void initialCapacity_twice() {
    var builder = Caffeine.newBuilder().initialCapacity(1);
    assertThrows(IllegalStateException.class, () -> builder.initialCapacity(1));
  }

  @Test
  void initialCapacity_small() {
    // can't check, so just assert that it builds
    var builder = Caffeine.newBuilder().initialCapacity(0);
    assertThat(builder.initialCapacity).isEqualTo(0);
    assertThat(builder.build()).isNotNull();
  }

  @Test
  void initialCapacity_large() {
    // don't build! just check that it configures
    var builder = Caffeine.newBuilder().initialCapacity(Integer.MAX_VALUE);
    assertThat(builder.initialCapacity).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- maximumSize --------------- */

  @Test
  void maximumSize_negative() {
    assertThrows(IllegalArgumentException.class, () -> Caffeine.newBuilder().maximumSize(-1));
  }

  @Test
  void maximumSize_twice() {
    var builder = Caffeine.newBuilder().maximumSize(1);
    assertThrows(IllegalStateException.class, () -> builder.maximumSize(1));
  }

  @Test
  void maximumSize_maximumWeight() {
    var builder = Caffeine.newBuilder().maximumWeight(1);
    assertThrows(IllegalStateException.class, () -> builder.maximumSize(1));
  }

  @Test
  void maximumSize_weigher() {
    var builder = Caffeine.newBuilder().weigher(Weigher.singletonWeigher());
    assertThrows(IllegalStateException.class, () -> builder.maximumSize(1));
  }

  @Test
  void maximumSize_small() {
    var builder = Caffeine.newBuilder().maximumSize(0);
    assertThat(builder.maximumSize).isEqualTo(0);
    var cache = builder.build();
    assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(0);
  }

  @Test
  void maximumSize_large() {
    var builder = Caffeine.newBuilder().maximumSize(Integer.MAX_VALUE);
    assertThat(builder.maximumSize).isEqualTo(Integer.MAX_VALUE);
    var cache = builder.build();
    assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- maximumWeight --------------- */

  @Test
  void maximumWeight_negative() {
    assertThrows(IllegalArgumentException.class, () -> Caffeine.newBuilder().maximumWeight(-1));
  }

  @Test
  void maximumWeight_twice() {
    var builder = Caffeine.newBuilder().maximumWeight(1);
    assertThrows(IllegalStateException.class, () -> builder.maximumWeight(1));
  }

  @Test
  void maximumWeight_noWeigher() {
    assertThrows(IllegalStateException.class, () -> Caffeine.newBuilder().maximumWeight(1).build());
  }

  @Test
  void maximumWeight_maximumSize() {
    var builder = Caffeine.newBuilder().maximumSize(1);
    assertThrows(IllegalStateException.class, () -> builder.maximumWeight(1));
  }

  @Test
  void maximumWeight_small() {
    var builder = Caffeine.newBuilder()
        .maximumWeight(0).weigher(Weigher.singletonWeigher());
    assertThat(builder.weigher).isSameInstanceAs(Weigher.singletonWeigher());
    assertThat(builder.maximumWeight).isEqualTo(0);
    var eviction = builder.build().policy().eviction().orElseThrow();
    assertThat(eviction.getMaximum()).isEqualTo(0);
    assertThat(eviction.isWeighted()).isTrue();
  }

  @Test
  void maximumWeight_large() {
    var builder = Caffeine.newBuilder()
        .maximumWeight(Integer.MAX_VALUE).weigher(Weigher.singletonWeigher());
    assertThat(builder.maximumWeight).isEqualTo(Integer.MAX_VALUE);
    assertThat(builder.weigher).isSameInstanceAs(Weigher.singletonWeigher());

    var eviction = builder.build().policy().eviction().orElseThrow();
    assertThat(eviction.getMaximum()).isEqualTo(Integer.MAX_VALUE);
    assertThat(eviction.isWeighted()).isTrue();
  }

  /* --------------- weigher --------------- */

  @Test
  void weigher_null() {
    Weigher<Object, Object> nullWeigher = nullRef();
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().weigher(nullWeigher));
  }

  @Test
  void weigher_twice() {
    var builder = Caffeine.newBuilder().weigher(Weigher.singletonWeigher());
    assertThrows(IllegalStateException.class, () -> builder.weigher(Weigher.singletonWeigher()));
  }

  @Test
  void weigher_maximumSize() {
    var builder = Caffeine.newBuilder().maximumSize(1);
    assertThrows(IllegalStateException.class, () -> builder.weigher(Weigher.singletonWeigher()));
  }

  @Test
  void weigher_noMaximumWeight() {
    assertThrows(IllegalStateException.class, () ->
        Caffeine.newBuilder().weigher(Weigher.singletonWeigher()).build());
  }

  @Test
  void weigher() {
    Weigher<Object, Object> weigher = (k, v) -> 0;
    var builder = Caffeine.newBuilder().maximumWeight(0).weigher(weigher);
    assertThat(builder.weigher).isSameInstanceAs(weigher);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- expireAfterAccess --------------- */

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterAccess_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().expireAfterAccess(-1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterAccess_expiry() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterAccess(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterAccess_twice() {
    var builder = Caffeine.newBuilder().expireAfterAccess(1, TimeUnit.MILLISECONDS);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterAccess(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterAccess_small() {
    var builder = Caffeine.newBuilder().expireAfterAccess(0, TimeUnit.MILLISECONDS);
    assertThat(builder.expireAfterAccessNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterAccess_large() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterAccessNanos).isEqualTo(Integer.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- expireAfterAccess: java.time --------------- */

  @Test
  void expireAfterAccess_duration_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(-1)));
  }

  @Test
  void expireAfterAccess_duration_expiry() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterAccess(Duration.ofMillis(1)));
  }

  @Test
  void expireAfterAccess_duration_twice() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterAccess(Duration.ofMillis(1)));
  }

  @Test
  void expireAfterAccess_duration() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(1));
    assertThat(builder.expireAfterAccessNanos).isEqualTo(Duration.ofMinutes(1).toNanos());
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void expireAfterAccess_duration_immediate() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Duration.ZERO);
    assertThat(builder.expireAfterAccessNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  void expireAfterAccess_duration_excessive() {
    var builder = Caffeine.newBuilder().expireAfterAccess(ChronoUnit.FOREVER.getDuration());
    assertThat(builder.expireAfterAccessNanos).isEqualTo(Long.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  /* --------------- expireAfterWrite --------------- */

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterWrite_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().expireAfterWrite(-1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterWrite_expiry() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterWrite(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterWrite_twice() {
    var builder = Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterWrite(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterWrite_small() {
    var builder = Caffeine.newBuilder().expireAfterWrite(0, TimeUnit.MILLISECONDS);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterWrite_large() {
    var builder = Caffeine.newBuilder()
        .expireAfterWrite(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Integer.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- expireAfterWrite: java.time --------------- */

  @Test
  void expireAfterWrite_duration_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(-1)));
  }

  @Test
  void expireAfterWrite_duration_expiry() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () -> builder.expireAfterWrite(Duration.ofMillis(1)));
  }

  @Test
  void expireAfterWrite_duration_twice() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () -> builder.expireAfterWrite(Duration.ofMillis(1)));
  }

  @Test
  void expireAfterWrite_duration() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1));
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Duration.ofMinutes(1).toNanos());
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterWrite_duration_immediate() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ZERO);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void expireAfterWrite_duration_excessive() {
    var builder = Caffeine.newBuilder().expireAfterWrite(ChronoUnit.FOREVER.getDuration());
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Long.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  /* --------------- expiry --------------- */

  @Test
  void expireAfter_null() {
    Expiry<Object, Object> nullExpiry = nullRef();
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().expireAfter(nullExpiry));
  }

  @Test
  void expireAfter_twice() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () -> builder.expireAfter(expiry));
  }

  @Test
  void expireAfter_access() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () -> builder.expireAfter(expiry));
  }

  @Test
  void expireAfter_write() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () -> builder.expireAfter(expiry));
  }

  @Test
  void expireAfter() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThat(builder.expiry).isSameInstanceAs(expiry);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- refreshAfterWrite --------------- */

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void refreshAfterWrite_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(-1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void refreshAfterWrite_twice() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThrows(IllegalStateException.class, () ->
        builder.refreshAfterWrite(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void refreshAfterWrite_noCacheLoader() {
    assertThrows(IllegalStateException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(1, TimeUnit.MILLISECONDS).build());
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void refreshAfterWrite_zero() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(0, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  void refreshAfterWrite() {
    var builder = Caffeine.newBuilder()
        .refreshAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(1));
    assertThat(builder.build(k -> k)).isNotNull();
  }

  /* --------------- refreshAfterWrite: java.time --------------- */

  @Test
  void refreshAfterWrite_duration_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(-1)));
  }

  @Test
  void refreshAfterWrite_duration_twice() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () ->
        builder.refreshAfterWrite(Duration.ofMillis(1)));
  }

  @Test
  void refreshAfterWrite_duration_noCacheLoader() {
    assertThrows(IllegalStateException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(1)).build());
  }

  @Test
  void refreshAfterWrite_duration_zero() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(Duration.ZERO));
  }

  @Test
  void refreshAfterWrite_duration() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(Duration.ofMinutes(1));
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(Duration.ofMinutes(1).toNanos());
    assertThat(builder.build(k -> k)).isNotNull();
  }

  @Test
  void refreshAfterWrite_excessive() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(ChronoUnit.FOREVER.getDuration());
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(Long.MAX_VALUE);
    assertThat(builder.build(k -> k)).isNotNull();
  }

  /* --------------- weakKeys --------------- */

  @Test
  void weakKeys_twice() {
    var builder = Caffeine.newBuilder().weakKeys();
    assertThrows(IllegalStateException.class, builder::weakKeys);
  }

  @Test
  void weakKeys() {
    var cache = Caffeine.newBuilder().weakKeys().build();
    assertThat(cache).isNotNull();
  }

  /* --------------- weakValues --------------- */

  @Test
  void weakValues_twice() {
    var builder = Caffeine.newBuilder().weakValues();
    assertThrows(IllegalStateException.class, builder::weakValues);
  }

  @Test
  void weakValues() {
    var cache = Caffeine.newBuilder().weakValues().build();
    assertThat(cache).isNotNull();
  }

  /* --------------- softValues --------------- */

  @Test
  void softValues_twice() {
    var builder = Caffeine.newBuilder().softValues();
    assertThrows(IllegalStateException.class, builder::softValues);
  }

  @Test
  void softValues() {
    var cache = Caffeine.newBuilder().softValues().build();
    assertThat(cache).isNotNull();
  }

  /* --------------- scheduler --------------- */

  @Test
  void scheduler_null() {
    Scheduler nullScheduler = nullRef();
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().scheduler(nullScheduler));
  }

  @Test
  void scheduler_twice() {
    var builder = Caffeine.newBuilder().scheduler(Scheduler.disabledScheduler());
    assertThrows(IllegalStateException.class, () ->
        builder.scheduler(Scheduler.disabledScheduler()));
  }

  @Test
  void scheduler_system() {
    var builder = Caffeine.newBuilder().scheduler(Scheduler.systemScheduler());
    assertThat(builder.getScheduler()).isSameInstanceAs(Scheduler.systemScheduler());
    assertThat(builder.build()).isNotNull();
  }

  @Test
  void scheduler_custom() {
    Scheduler scheduler = (executor, task, delay, unit) -> DisabledFuture.instance();
    var builder = Caffeine.newBuilder().scheduler(scheduler);
    assertThat(((GuardedScheduler) builder.getScheduler()).delegate).isSameInstanceAs(scheduler);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- executor --------------- */

  @Test
  void executor_null() {
    Executor nullExecutor = nullRef();
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().executor(nullExecutor));
  }

  @Test
  void executor_twice() {
    var builder = Caffeine.newBuilder().executor(directExecutor());
    assertThrows(IllegalStateException.class, () -> builder.executor(directExecutor()));
  }

  @Test
  void executor() {
    var builder = Caffeine.newBuilder().executor(directExecutor());
    assertThat(builder.getExecutor()).isSameInstanceAs(directExecutor());
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- ticker --------------- */

  @Test
  void ticker_null() {
    Ticker nullTicker = nullRef();
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().ticker(nullTicker));
  }

  @Test
  void ticker_twice() {
    var builder = Caffeine.newBuilder().ticker(Ticker.systemTicker());
    assertThrows(IllegalStateException.class, () -> builder.ticker(Ticker.systemTicker()));
  }

  @Test
  void ticker() {
    Ticker ticker = new FakeTicker()::read;
    var builder = Caffeine.newBuilder().ticker(ticker);
    assertThat(builder.ticker).isSameInstanceAs(ticker);
    assertThat(builder.getTicker()).isSameInstanceAs(Ticker.disabledTicker());
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- stats --------------- */

  @Test
  void recordStats_null() {
    assertThrows(NullPointerException.class,
        () -> Caffeine.newBuilder().recordStats(nullSupplier()));
  }

  @Test
  void recordStats_twice() {
    var type = IllegalStateException.class;
    Supplier<StatsCounter> supplier = Mockito::mock;
    assertThrows(type, () -> Caffeine.newBuilder().recordStats().recordStats());
    assertThrows(type, () -> Caffeine.newBuilder().recordStats(supplier).recordStats());
    assertThrows(type, () -> Caffeine.newBuilder().recordStats().recordStats(supplier));
    assertThrows(type, () -> Caffeine.newBuilder().recordStats(supplier).recordStats(supplier));
  }

  @Test
  void recordStats() {
    var builder = Caffeine.newBuilder().recordStats();
    assertThat(builder.statsCounterSupplier).isEqualTo(Caffeine.ENABLED_STATS_COUNTER_SUPPLIER);
    assertThat(builder.build()).isNotNull();
  }

  @Test
  void recordStats_custom() {
    StatsCounter statsCounter = Mockito.mock();
    var builder = Caffeine.newBuilder().recordStats(() -> statsCounter);
    assertThat(builder.statsCounterSupplier).isNotNull();

    var counter1 = builder.statsCounterSupplier.get();
    var counter2 = builder.statsCounterSupplier.get();
    assertThat(counter1).isNotSameInstanceAs(counter2);
    assertThat(counter1).isNotNull();
    assertThat(counter2).isNotNull();

    assertThat(counter1.getClass().getName())
        .isEqualTo("com.github.benmanes.caffeine.cache.stats.GuardedStatsCounter");
    counter1.recordEviction(1, RemovalCause.SIZE);
    verify(statsCounter).recordEviction(1, RemovalCause.SIZE);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- removalListener --------------- */

  @Test
  void removalListener_null() {
    RemovalListener<Object, Object> nullListener = nullRef();
    assertThrows(NullPointerException.class, () ->
        Caffeine.newBuilder().removalListener(nullListener));
  }

  @Test
  void removalListener_twice() {
    var builder = Caffeine.newBuilder().removalListener((k, v, c) -> {});
    assertThrows(IllegalStateException.class, () -> builder.removalListener((k, v, c) -> {}));
  }

  @Test
  void removalListener() {
    RemovalListener<Object, Object> removalListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().removalListener(removalListener);
    assertThat(builder.getRemovalListener(false)).isSameInstanceAs(removalListener);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- evictionListener --------------- */

  @Test
  void evictionListener_null() {
    RemovalListener<Object, Object> nullListener = nullRef();
    assertThrows(NullPointerException.class, () ->
        Caffeine.newBuilder().evictionListener(nullListener));
  }

  @Test
  void evictionListener_twice() {
    var builder = Caffeine.newBuilder().evictionListener((k, v, c) -> {});
    assertThrows(IllegalStateException.class, () -> builder.evictionListener((k, v, c) -> {}));
  }

  @Test
  void evictionListener() {
    RemovalListener<Object, Object> removalListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().evictionListener(removalListener);
    assertThat(builder.evictionListener).isSameInstanceAs(removalListener);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- Static helpers --------------- */

  @Test
  void ceilingPowerOfTwo_long() {
    assertThat(Caffeine.ceilingPowerOfTwo(1L)).isEqualTo(1L);
    assertThat(Caffeine.ceilingPowerOfTwo(2L)).isEqualTo(2L);
    assertThat(Caffeine.ceilingPowerOfTwo(3L)).isEqualTo(4L);
    assertThat(Caffeine.ceilingPowerOfTwo(1024L)).isEqualTo(1024L);
    assertThat(Caffeine.ceilingPowerOfTwo(1025L)).isEqualTo(2048L);
  }

  @Test
  void toNanosSaturated_positive() {
    assertThat(Caffeine.toNanosSaturated(Duration.ofNanos(100L))).isEqualTo(100L);
    assertThat(Caffeine.toNanosSaturated(Duration.ofNanos(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    // Just below MAX_DURATION: not saturated — returns actual toNanos
    assertThat(Caffeine.toNanosSaturated(Duration.ofSeconds(Long.MAX_VALUE / 1_000_000_000, 0)))
        .isLessThan(Long.MAX_VALUE);
    // Above MAX_DURATION: saturates to Long.MAX_VALUE
    assertThat(Caffeine.toNanosSaturated(Duration.ofDays(365L * 500))).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void toNanosSaturated_negative() {
    assertThat(Caffeine.toNanosSaturated(Duration.ofNanos(-100L))).isEqualTo(-100L);
    assertThat(Caffeine.toNanosSaturated(Duration.ofNanos(Long.MIN_VALUE))).isEqualTo(Long.MIN_VALUE);
    // Below MIN_DURATION: saturates to Long.MIN_VALUE
    assertThat(Caffeine.toNanosSaturated(Duration.ofDays(-365L * 500))).isEqualTo(Long.MIN_VALUE);
  }

  @Test
  void getInitialCapacity_default() {
    assertThat(Caffeine.newBuilder().getInitialCapacity())
        .isEqualTo(Caffeine.DEFAULT_INITIAL_CAPACITY);
  }

  @Test
  void getInitialCapacity_set() {
    assertThat(Caffeine.newBuilder().initialCapacity(42).getInitialCapacity()).isEqualTo(42);
  }

  @Test
  void isStrongValues_default() {
    assertThat(Caffeine.newBuilder().isStrongValues()).isTrue();
    assertThat(Caffeine.newBuilder().isWeakValues()).isFalse();
  }

  @Test
  void isWeakValues_weak() {
    var builder = Caffeine.newBuilder().weakValues();
    assertThat(builder.isStrongValues()).isFalse();
    assertThat(builder.isWeakValues()).isTrue();
  }

  @Test
  void isWeakValues_soft() {
    var builder = Caffeine.newBuilder().softValues();
    assertThat(builder.isStrongValues()).isFalse();
    assertThat(builder.isWeakValues()).isFalse();
  }

  @Test
  void toString_unconfigured() {
    assertThat(Caffeine.newBuilder().toString()).isEqualTo("Caffeine{}");
  }

  @Test
  void toString_configured() {
    assertThat(Caffeine.newBuilder().maximumSize(10).toString())
        .isEqualTo("Caffeine{maximumSize=10}");
  }

  @Test
  void isBounded_default() {
    assertThat(Caffeine.newBuilder().isBounded()).isFalse();
  }

  @Test
  void isBounded_maximumSize() {
    assertThat(Caffeine.newBuilder().maximumSize(10).isBounded()).isTrue();
  }

  @Test
  void isBounded_maximumWeight() {
    assertThat(Caffeine.newBuilder().maximumWeight(10).weigher((k, v) -> 1).isBounded()).isTrue();
  }

  @Test
  void isBounded_expireAfterAccess() {
    assertThat(Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(1)).isBounded()).isTrue();
  }

  @Test
  void isBounded_expireAfterWrite() {
    assertThat(Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).isBounded()).isTrue();
  }

  @Test
  void isBounded_weakKeys() {
    assertThat(Caffeine.newBuilder().weakKeys().isBounded()).isTrue();
  }

  @Test
  void isBounded_weakValues() {
    assertThat(Caffeine.newBuilder().weakValues().isBounded()).isTrue();
  }

  @Test
  void getWeigher_default_isSingleton() {
    Weigher<Object, Object> weigher = Caffeine.newBuilder().getWeigher(false);
    assertThat(weigher).isSameInstanceAs(Weigher.singletonWeigher());
  }

  @Test
  void getWeigher_custom_wrappedAsBounded() {
    Weigher<Object, Object> custom = (k, v) -> 1;
    Weigher<Object, Object> result = Caffeine.newBuilder()
        .maximumWeight(10).weigher(custom).getWeigher(false);
    assertThat(result).isNotSameInstanceAs(custom);
    assertThat(result).isNotSameInstanceAs(Weigher.singletonWeigher());
  }

  @Test
  void getExpiry_nullForNoExpiry() {
    assertThat(Caffeine.newBuilder().getExpiry(false)).isNull();
    assertThat(Caffeine.newBuilder().getExpiry(true)).isNull();
  }

  @Test
  void getExpiry_syncReturnsRaw() {
    Expiry<Object, Object> expiry = Expiry.creating((k, v) -> Duration.ofMinutes(1));
    var result = Caffeine.newBuilder().expireAfter(expiry).getExpiry(false);
    assertThat(result).isSameInstanceAs(expiry);
  }

  @Test
  void getExpiry_asyncWrapsRaw() {
    Expiry<Object, Object> expiry = Expiry.creating((k, v) -> Duration.ofMinutes(1));
    var result = Caffeine.newBuilder().expireAfter(expiry).getExpiry(true);
    assertThat(result).isNotNull();
    assertThat(result).isNotSameInstanceAs(expiry);
  }

  @Test
  void getTicker_disabledWhenNoExpiration() {
    assertThat(Caffeine.newBuilder().getTicker()).isSameInstanceAs(Ticker.disabledTicker());
  }

  @Test
  void getTicker_systemWhenExpiring() {
    assertThat(Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).getTicker())
        .isSameInstanceAs(Ticker.systemTicker());
  }

  @Test
  void getTicker_customWhenExpiring() {
    Ticker custom = () -> 0L;
    assertThat(Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1))
        .ticker(custom).getTicker()).isSameInstanceAs(custom);
  }

  @Test
  void getEvictionListener_nullByDefault() {
    assertThat(Caffeine.newBuilder().getEvictionListener(false)).isNull();
    assertThat(Caffeine.newBuilder().getEvictionListener(true)).isNull();
  }

  @Test
  void getEvictionListener_syncReturnsRaw() {
    RemovalListener<Object, Object> listener = (k, v, c) -> {};
    var result = Caffeine.newBuilder().evictionListener(listener).getEvictionListener(false);
    assertThat(result).isSameInstanceAs(listener);
  }

  @Test
  void getEvictionListener_asyncWrapsRaw() {
    RemovalListener<Object, Object> listener = (k, v, c) -> {};
    var result = Caffeine.newBuilder().evictionListener(listener).getEvictionListener(true);
    assertThat(result).isNotNull();
    assertThat(result).isNotSameInstanceAs(listener);
  }

  @Test
  void build_withMaximumWeight_requiresWeigher() {
    assertThrows(IllegalStateException.class,
        () -> Caffeine.newBuilder().maximumWeight(10).build());
  }

  @Test
  void buildAsync_withMaximumWeight_requiresWeigher() {
    assertThrows(IllegalStateException.class,
        () -> Caffeine.newBuilder().maximumWeight(10).buildAsync());
  }

  @Test
  void buildAsync_refreshAfterWrite_requiresLoadingCache() {
    assertThrows(IllegalStateException.class,
        () -> Caffeine.newBuilder().refreshAfterWrite(Duration.ofMinutes(1)).buildAsync());
  }

  @Test
  void build_unbounded_returnsUnboundedLoadingCache() {
    CacheLoader<Object, Object> loader = key -> key;
    var cache = Caffeine.newBuilder().build(loader);
    assertThat(cache).isInstanceOf(UnboundedLocalCache.UnboundedLocalLoadingCache.class);
  }

  @Test
  void build_bounded_returnsBoundedLoadingCache() {
    CacheLoader<Object, Object> loader = key -> key;
    var cache = Caffeine.newBuilder().maximumSize(10).build(loader);
    assertThat(cache).isInstanceOf(BoundedLocalCache.BoundedLocalLoadingCache.class);
  }

  @Test
  void buildAsync_unbounded_returnsUnboundedAsyncCache() {
    var cache = Caffeine.newBuilder().buildAsync();
    assertThat(cache).isInstanceOf(UnboundedLocalCache.UnboundedLocalAsyncCache.class);
  }

  @Test
  void buildAsync_bounded_returnsBoundedAsyncCache() {
    var cache = Caffeine.newBuilder().maximumSize(10).buildAsync();
    assertThat(cache).isInstanceOf(BoundedLocalCache.BoundedLocalAsyncCache.class);
  }

  @Test
  void buildAsync_loader_unbounded_returnsUnboundedAsyncLoadingCache() {
    CacheLoader<Object, Object> loader = key -> key;
    var cache = Caffeine.newBuilder().buildAsync(loader);
    assertThat(cache).isInstanceOf(UnboundedLocalCache.UnboundedLocalAsyncLoadingCache.class);
  }

  @Test
  void buildAsync_loader_bounded_returnsBoundedAsyncLoadingCache() {
    CacheLoader<Object, Object> loader = key -> key;
    var cache = Caffeine.newBuilder().maximumSize(10).buildAsync(loader);
    assertThat(cache).isInstanceOf(BoundedLocalCache.BoundedLocalAsyncLoadingCache.class);
  }

  @Test
  void buildAsync_loader_refreshAfterWrite_returnsBoundedAsyncLoadingCache() {
    CacheLoader<Object, Object> loader = key -> key;
    var cache = Caffeine.newBuilder().refreshAfterWrite(Duration.ofMinutes(1)).buildAsync(loader);
    assertThat(cache).isInstanceOf(BoundedLocalCache.BoundedLocalAsyncLoadingCache.class);
  }
}
