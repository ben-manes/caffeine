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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Maximum;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.google.common.testing.FakeTicker;
import com.google.common.testing.NullPointerTester;

/**
 * A test for the builder methods.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineTest {
  @Mock StatsCounter statsCounter;
  @Mock Expiry<Object, Object> expiry;
  @Mock CacheLoader<Object, Object> loader;

  @BeforeClass
  public void beforeClass() throws Exception {
    MockitoAnnotations.openMocks(this).close();
  }

  @Test
  public void nullParameters() {
    var npeTester = new NullPointerTester();
    npeTester.testAllPublicInstanceMethods(Caffeine.newBuilder());
  }

  @Test
  public void unconfigured() {
    assertThat(Caffeine.newBuilder().build()).isNotNull();
    assertThat(Caffeine.newBuilder().build(loader)).isNotNull();
    assertThat(Caffeine.newBuilder().buildAsync()).isNotNull();
    assertThat(Caffeine.newBuilder().buildAsync(loader)).isNotNull();
    assertThat(Caffeine.newBuilder().toString()).isEqualTo(Caffeine.newBuilder().toString());
  }

  @Test
  public void configured() {
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
  public void fromSpec_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.from((CaffeineSpec) null));
  }

  @Test
  public void fromSpec_lenientParsing() {
    var cache = Caffeine.from(CaffeineSpec.parse("maximumSize=100")).weigher((k, v) -> 0).build();
    assertThat(cache).isNotNull();
  }

  @Test
  public void fromSpec() {
    assertThat(Caffeine.from(CaffeineSpec.parse(""))).isNotNull();
  }

  @Test
  public void fromString_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.from((String) null));
  }

  @Test
  public void fromString_lenientParsing() {
    var cache = Caffeine.from("maximumSize=100").weigher((k, v) -> 0).build();
    assertThat(cache).isNotNull();
  }

  @Test
  public void fromString() {
    assertThat(Caffeine.from("")).isNotNull();
  }

  @Test(dataProviderClass = CacheProvider.class, dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = {InitialCapacity.DEFAULT, InitialCapacity.FULL}, compute = Compute.SYNC)
  public void string(CacheContext context) {
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
  public void calculateHashMapCapacity() {
    Iterable<Integer> iterable = List.of(1, 2, 3)::iterator;
    assertThat(Caffeine.calculateHashMapCapacity(iterable)).isEqualTo(16);
    assertThat(Caffeine.calculateHashMapCapacity(List.of(1, 2, 3))).isEqualTo(4);
  }

  /* --------------- loading --------------- */

  @Test
  public void loading_nullLoader() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().build(null));
  }

  /* --------------- async --------------- */

  @Test
  public void async_weakValues() {
    var builder = Caffeine.newBuilder().weakValues();
    assertThrows(IllegalStateException.class, () -> builder.buildAsync(loader));
  }

  @Test
  public void async_softValues() {
    var builder = Caffeine.newBuilder().softValues();
    assertThrows(IllegalStateException.class, () -> builder.buildAsync(loader));
  }

  @Test
  public void async_weakKeys_evictionListener() {
    RemovalListener<Object, Object> evictionListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().weakKeys().evictionListener(evictionListener);
    assertThrows(IllegalStateException.class, builder::buildAsync);
  }

  /* --------------- async loader --------------- */

  @Test
  public void asyncLoader_nullLoader() {
    assertThrows(NullPointerException.class, () ->
        Caffeine.newBuilder().buildAsync((CacheLoader<Object, Object>) null));
    assertThrows(NullPointerException.class, () ->
        Caffeine.newBuilder().buildAsync((AsyncCacheLoader<Object, Object>) null));
  }

  @Test
  public void asyncLoader() {
    AsyncCacheLoader<Object, Object> asyncLoader = loader::asyncLoad;
    var cache = Caffeine.newBuilder().buildAsync(asyncLoader);
    assertThat(cache).isNotNull();
  }

  @Test
  public void asyncLoader_weakValues() {
    var builder = Caffeine.newBuilder().weakValues();
    assertThrows(IllegalStateException.class, () -> builder.buildAsync(loader));
  }

  @Test
  public void asyncLoader_softValues() {
    var builder = Caffeine.newBuilder().softValues();
    assertThrows(IllegalStateException.class, () -> builder.buildAsync(loader));
  }

  @Test
  public void async_asyncLoader_weakKeys_evictionListener() {
    RemovalListener<Object, Object> evictionListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().weakKeys().evictionListener(evictionListener);
    assertThrows(IllegalStateException.class, () -> builder.buildAsync(loader));
  }

  /* --------------- initialCapacity --------------- */

  @Test
  public void initialCapacity_negative() {
    assertThrows(IllegalArgumentException.class, () -> Caffeine.newBuilder().initialCapacity(-1));
  }

  @Test
  public void initialCapacity_twice() {
    var builder = Caffeine.newBuilder().initialCapacity(1);
    assertThrows(IllegalStateException.class, () -> builder.initialCapacity(1));
  }

  @Test
  public void initialCapacity_small() {
    // can't check, so just assert that it builds
    var builder = Caffeine.newBuilder().initialCapacity(0);
    assertThat(builder.initialCapacity).isEqualTo(0);
    assertThat(builder.build()).isNotNull();
  }

  @Test
  public void initialCapacity_large() {
    // don't build! just check that it configures
    var builder = Caffeine.newBuilder().initialCapacity(Integer.MAX_VALUE);
    assertThat(builder.initialCapacity).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- maximumSize --------------- */

  @Test
  public void maximumSize_negative() {
    assertThrows(IllegalArgumentException.class, () -> Caffeine.newBuilder().maximumSize(-1));
  }

  @Test
  public void maximumSize_twice() {
    var builder = Caffeine.newBuilder().maximumSize(1);
    assertThrows(IllegalStateException.class, () -> builder.maximumSize(1));
  }

  @Test
  public void maximumSize_maximumWeight() {
    var builder = Caffeine.newBuilder().maximumWeight(1);
    assertThrows(IllegalStateException.class, () -> builder.maximumSize(1));
  }

  @Test
  public void maximumSize_weigher() {
    var builder = Caffeine.newBuilder().weigher(Weigher.singletonWeigher());
    assertThrows(IllegalStateException.class, () -> builder.maximumSize(1));
  }

  @Test
  public void maximumSize_small() {
    var builder = Caffeine.newBuilder().maximumSize(0);
    assertThat(builder.maximumSize).isEqualTo(0);
    var cache = builder.build();
    assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(0);
  }

  @Test
  public void maximumSize_large() {
    var builder = Caffeine.newBuilder().maximumSize(Integer.MAX_VALUE);
    assertThat(builder.maximumSize).isEqualTo(Integer.MAX_VALUE);
    var cache = builder.build();
    assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- maximumWeight --------------- */

  @Test
  public void maximumWeight_negative() {
    assertThrows(IllegalArgumentException.class, () -> Caffeine.newBuilder().maximumWeight(-1));
  }

  @Test
  public void maximumWeight_twice() {
    var builder = Caffeine.newBuilder().maximumWeight(1);
    assertThrows(IllegalStateException.class, () -> builder.maximumWeight(1));
  }

  @Test
  public void maximumWeight_noWeigher() {
    assertThrows(IllegalStateException.class, () -> Caffeine.newBuilder().maximumWeight(1).build());
  }

  @Test
  public void maximumWeight_maximumSize() {
    var builder = Caffeine.newBuilder().maximumSize(1);
    assertThrows(IllegalStateException.class, () -> builder.maximumWeight(1));
  }

  @Test
  public void maximumWeight_small() {
    var builder = Caffeine.newBuilder()
        .maximumWeight(0).weigher(Weigher.singletonWeigher());
    assertThat(builder.weigher).isSameInstanceAs(Weigher.singletonWeigher());
    assertThat(builder.maximumWeight).isEqualTo(0);
    var eviction = builder.build().policy().eviction().orElseThrow();
    assertThat(eviction.getMaximum()).isEqualTo(0);
    assertThat(eviction.isWeighted()).isTrue();
  }

  @Test
  public void maximumWeight_large() {
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
  public void weigher_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().weigher(null));
  }

  @Test
  public void weigher_twice() {
    var builder = Caffeine.newBuilder().weigher(Weigher.singletonWeigher());
    assertThrows(IllegalStateException.class, () -> builder.weigher(Weigher.singletonWeigher()));
  }

  @Test
  public void weigher_maximumSize() {
    var builder = Caffeine.newBuilder().maximumSize(1);
    assertThrows(IllegalStateException.class, () -> builder.weigher(Weigher.singletonWeigher()));
  }

  @Test
  public void weigher_noMaximumWeight() {
    assertThrows(IllegalStateException.class, () ->
        Caffeine.newBuilder().weigher(Weigher.singletonWeigher()).build());
  }

  @Test
  public void weigher() {
    Weigher<Object, Object> weigher = (k, v) -> 0;
    var builder = Caffeine.newBuilder().maximumWeight(0).weigher(weigher);
    assertThat(builder.weigher).isSameInstanceAs(weigher);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- expireAfterAccess --------------- */

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterAccess_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().expireAfterAccess(-1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterAccess_expiry() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterAccess(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterAccess_twice() {
    var builder = Caffeine.newBuilder().expireAfterAccess(1, TimeUnit.MILLISECONDS);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterAccess(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterAccess_small() {
    var builder = Caffeine.newBuilder().expireAfterAccess(0, TimeUnit.MILLISECONDS);
    assertThat(builder.expireAfterAccessNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterAccess_large() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterAccessNanos).isEqualTo(Integer.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- expireAfterAccess: java.time --------------- */

  @Test
  public void expireAfterAccess_duration_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(-1)));
  }

  @Test
  public void expireAfterAccess_duration_expiry() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterAccess(Duration.ofMillis(1)));
  }

  @Test
  public void expireAfterAccess_duration_twice() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterAccess(Duration.ofMillis(1)));
  }

  @Test
  public void expireAfterAccess_duration() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(1));
    assertThat(builder.expireAfterAccessNanos).isEqualTo(Duration.ofMinutes(1).toNanos());
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  public void expireAfterAccess_duration_immediate() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Duration.ZERO);
    assertThat(builder.expireAfterAccessNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  public void expireAfterAccess_duration_excessive() {
    var builder = Caffeine.newBuilder().expireAfterAccess(ChronoUnit.FOREVER.getDuration());
    assertThat(builder.expireAfterAccessNanos).isEqualTo(Long.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  /* --------------- expireAfterWrite --------------- */

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterWrite_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().expireAfterWrite(-1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterWrite_expiry() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterWrite(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterWrite_twice() {
    var builder = Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThrows(IllegalStateException.class, () ->
        builder.expireAfterWrite(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterWrite_small() {
    var builder = Caffeine.newBuilder().expireAfterWrite(0, TimeUnit.MILLISECONDS);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterWrite_large() {
    var builder = Caffeine.newBuilder()
        .expireAfterWrite(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Integer.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- expireAfterWrite: java.time --------------- */

  @Test
  public void expireAfterWrite_duration_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(-1)));
  }

  @Test
  public void expireAfterWrite_duration_expiry() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () -> builder.expireAfterWrite(Duration.ofMillis(1)));
  }

  @Test
  public void expireAfterWrite_duration_twice() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () -> builder.expireAfterWrite(Duration.ofMillis(1)));
  }

  @Test
  public void expireAfterWrite_duration() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1));
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Duration.ofMinutes(1).toNanos());
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterWrite_duration_immediate() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ZERO);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void expireAfterWrite_duration_excessive() {
    var builder = Caffeine.newBuilder().expireAfterWrite(ChronoUnit.FOREVER.getDuration());
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Long.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  /* --------------- expiry --------------- */

  @Test
  public void expireAfter_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().expireAfter(null));
  }

  @Test
  public void expireAfter_twice() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThrows(IllegalStateException.class, () -> builder.expireAfter(expiry));
  }

  @Test
  public void expireAfter_access() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () -> builder.expireAfter(expiry));
  }

  @Test
  public void expireAfter_write() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () -> builder.expireAfter(expiry));
  }

  @Test
  public void expireAfter() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThat(builder.expiry).isSameInstanceAs(expiry);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- refreshAfterWrite --------------- */

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void refreshAfterWrite_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(-1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void refreshAfterWrite_twice() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThrows(IllegalStateException.class, () ->
        builder.refreshAfterWrite(1, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void refreshAfterWrite_noCacheLoader() {
    assertThrows(IllegalStateException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(1, TimeUnit.MILLISECONDS).build());
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void refreshAfterWrite_zero() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(0, TimeUnit.MILLISECONDS));
  }

  @Test
  @SuppressWarnings("PreferJavaTimeOverload")
  public void refreshAfterWrite() {
    var builder = Caffeine.newBuilder()
        .refreshAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(1));
    assertThat(builder.build(k -> k)).isNotNull();
  }

  /* --------------- refreshAfterWrite: java.time --------------- */

  @Test
  public void refreshAfterWrite_duration_negative() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(-1)));
  }

  @Test
  public void refreshAfterWrite_duration_twice() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(1));
    assertThrows(IllegalStateException.class, () ->
        builder.refreshAfterWrite(Duration.ofMillis(1)));
  }

  @Test
  public void refreshAfterWrite_duration_noCacheLoader() {
    assertThrows(IllegalStateException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(1)).build());
  }

  @Test
  public void refreshAfterWrite_duration_zero() {
    assertThrows(IllegalArgumentException.class, () ->
        Caffeine.newBuilder().refreshAfterWrite(Duration.ZERO));
  }

  @Test
  public void refreshAfterWrite_duration() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(Duration.ofMinutes(1));
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(Duration.ofMinutes(1).toNanos());
    assertThat(builder.build(k -> k)).isNotNull();
  }

  @Test
  public void refreshAfterWrite_excessive() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(ChronoUnit.FOREVER.getDuration());
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(Long.MAX_VALUE);
    assertThat(builder.build(k -> k)).isNotNull();
  }

  /* --------------- weakKeys --------------- */

  @Test
  public void weakKeys_twice() {
    var builder = Caffeine.newBuilder().weakKeys();
    assertThrows(IllegalStateException.class, builder::weakKeys);
  }

  @Test
  public void weakKeys() {
    var cache = Caffeine.newBuilder().weakKeys().build();
    assertThat(cache).isNotNull();
  }

  /* --------------- weakValues --------------- */

  @Test
  public void weakValues_twice() {
    var builder = Caffeine.newBuilder().weakValues();
    assertThrows(IllegalStateException.class, builder::weakValues);
  }

  @Test
  public void weakValues() {
    var cache = Caffeine.newBuilder().weakValues().build();
    assertThat(cache).isNotNull();
  }

  /* --------------- softValues --------------- */

  @Test
  public void softValues_twice() {
    var builder = Caffeine.newBuilder().softValues();
    assertThrows(IllegalStateException.class, builder::softValues);
  }

  @Test
  public void softValues() {
    var cache = Caffeine.newBuilder().softValues().build();
    assertThat(cache).isNotNull();
  }

  /* --------------- scheduler --------------- */

  @Test
  public void scheduler_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().scheduler(null));
  }

  @Test
  public void scheduler_twice() {
    var builder = Caffeine.newBuilder().scheduler(Scheduler.disabledScheduler());
    assertThrows(IllegalStateException.class, () ->
        builder.scheduler(Scheduler.disabledScheduler()));
  }

  @Test
  public void scheduler_system() {
    var builder = Caffeine.newBuilder().scheduler(Scheduler.systemScheduler());
    assertThat(builder.getScheduler()).isSameInstanceAs(Scheduler.systemScheduler());
    assertThat(builder.build()).isNotNull();
  }

  @Test
  public void scheduler_custom() {
    Scheduler scheduler = (executor, task, delay, unit) -> DisabledFuture.INSTANCE;
    var builder = Caffeine.newBuilder().scheduler(scheduler);
    assertThat(((GuardedScheduler) builder.getScheduler()).delegate).isSameInstanceAs(scheduler);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- executor --------------- */

  @Test
  public void executor_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().executor(null));
  }

  @Test
  public void executor_twice() {
    var builder = Caffeine.newBuilder().executor(directExecutor());
    assertThrows(IllegalStateException.class, () -> builder.executor(directExecutor()));
  }

  @Test
  public void executor() {
    var builder = Caffeine.newBuilder().executor(directExecutor());
    assertThat(builder.getExecutor()).isSameInstanceAs(directExecutor());
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- ticker --------------- */

  @Test
  public void ticker_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().ticker(null));
  }

  @Test
  public void ticker_twice() {
    var builder = Caffeine.newBuilder().ticker(Ticker.systemTicker());
    assertThrows(IllegalStateException.class, () -> builder.ticker(Ticker.systemTicker()));
  }

  @Test
  public void ticker() {
    Ticker ticker = new FakeTicker()::read;
    var builder = Caffeine.newBuilder().ticker(ticker);
    assertThat(builder.ticker).isSameInstanceAs(ticker);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- stats --------------- */

  @Test
  public void recordStats_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().recordStats(null));
  }

  @Test
  public void recordStats_twice() {
    var type = IllegalStateException.class;
    Supplier<StatsCounter> supplier = () -> statsCounter;
    assertThrows(type, () -> Caffeine.newBuilder().recordStats().recordStats());
    assertThrows(type, () -> Caffeine.newBuilder().recordStats(supplier).recordStats());
    assertThrows(type, () -> Caffeine.newBuilder().recordStats().recordStats(supplier));
    assertThrows(type, () -> Caffeine.newBuilder().recordStats(supplier).recordStats(supplier));
  }

  @Test
  public void recordStats() {
    var builder = Caffeine.newBuilder().recordStats();
    assertThat(builder.statsCounterSupplier).isEqualTo(Caffeine.ENABLED_STATS_COUNTER_SUPPLIER);
    assertThat(builder.build()).isNotNull();
  }

  @Test
  public void recordStats_custom() {
    Supplier<StatsCounter> supplier = () -> statsCounter;
    var builder = Caffeine.newBuilder().recordStats(supplier);
    builder.statsCounterSupplier.get().recordEviction(1, RemovalCause.SIZE);
    verify(statsCounter).recordEviction(1, RemovalCause.SIZE);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- removalListener --------------- */

  @Test
  public void removalListener_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().removalListener(null));
  }

  @Test
  public void removalListener_twice() {
    var builder = Caffeine.newBuilder().removalListener((k, v, c) -> {});
    assertThrows(IllegalStateException.class, () -> builder.removalListener((k, v, c) -> {}));
  }

  @Test
  public void removalListener() {
    RemovalListener<Object, Object> removalListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().removalListener(removalListener);
    assertThat(builder.getRemovalListener(false)).isSameInstanceAs(removalListener);
    assertThat(builder.build()).isNotNull();
  }

  /* --------------- removalListener --------------- */

  @Test
  public void evictionListener_null() {
    assertThrows(NullPointerException.class, () -> Caffeine.newBuilder().evictionListener(null));
  }

  @Test
  public void evictionListener_twice() {
    var builder = Caffeine.newBuilder().evictionListener((k, v, c) -> {});
    assertThrows(IllegalStateException.class, () -> builder.evictionListener((k, v, c) -> {}));
  }

  @Test
  public void evictionListener() {
    RemovalListener<Object, Object> removalListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().evictionListener(removalListener);
    assertThat(builder.evictionListener).isSameInstanceAs(removalListener);
    assertThat(builder.build()).isNotNull();
  }
}
