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
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
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
@SuppressWarnings({"PreferJavaTimeOverload", "CheckReturnValue"})
public final class CaffeineTest {
  @Mock StatsCounter statsCounter;
  @Mock Expiry<Object, Object> expiry;
  @Mock CacheLoader<Object, Object> loader;

  AutoCloseable mocks;

  @BeforeClass
  public void beforeClass() {
    mocks = MockitoAnnotations.openMocks(this);
  }

  @AfterClass
  public void afterClass() throws Exception {
    mocks.close();
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
        .expireAfterAccess(1, TimeUnit.SECONDS).expireAfterWrite(1, TimeUnit.SECONDS)
        .removalListener((k, v, c) -> {}).recordStats();
    assertThat(configured.build()).isNotNull();
    assertThat(configured.buildAsync()).isNotNull();
    assertThat(configured.build(loader)).isNotNull();
    assertThat(configured.buildAsync(loader)).isNotNull();

    assertThat(configured.refreshAfterWrite(1, TimeUnit.SECONDS).toString())
        .isNotEqualTo(Caffeine.newBuilder().toString());
    assertThat(Caffeine.newBuilder().maximumSize(1).toString())
        .isNotEqualTo(Caffeine.newBuilder().maximumWeight(1).toString());
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void fromSpec_null() {
    Caffeine.from((CaffeineSpec) null);
  }

  @Test
  public void fromSpec_lenientParsing() {
    Caffeine.from(CaffeineSpec.parse("maximumSize=100")).weigher((k, v) -> 0).build();
  }

  @Test
  public void fromSpec() {
    assertThat(Caffeine.from(CaffeineSpec.parse(""))).isNotNull();
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void fromString_null() {
    Caffeine.from((String) null);
  }

  @Test
  public void fromString_lenientParsing() {
    Caffeine.from("maximumSize=100").weigher((k, v) -> 0).build();
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

  @Test(expectedExceptions = NullPointerException.class)
  public void loading_nullLoader() {
    Caffeine.newBuilder().build(null);
  }

  /* --------------- async --------------- */

  @Test(expectedExceptions = IllegalStateException.class)
  public void async_weakValues() {
    Caffeine.newBuilder().weakValues().buildAsync(loader);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void async_softValues() {
    Caffeine.newBuilder().softValues().buildAsync(loader);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void async_weakKeys_evictionListener() {
    RemovalListener<Object, Object> evictionListener = (k, v, c) -> {};
    Caffeine.newBuilder().weakKeys().evictionListener(evictionListener).buildAsync();
  }

  /* --------------- async loader --------------- */

  @Test
  public void asyncLoader_nullLoader() {
    try {
      Caffeine.newBuilder().buildAsync((CacheLoader<Object, Object>) null);
      Assert.fail();
    } catch (NullPointerException expected) {}

    try {
      Caffeine.newBuilder().buildAsync((AsyncCacheLoader<Object, Object>) null);
      Assert.fail();
    } catch (NullPointerException expected) {}
  }

  @Test
  @SuppressWarnings("UnnecessaryMethodReference")
  public void asyncLoader() {
    Caffeine.newBuilder().buildAsync(loader::asyncLoad);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void asyncLoader_weakValues() {
    Caffeine.newBuilder().weakValues().buildAsync(loader);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void asyncLoader_softValues() {
    Caffeine.newBuilder().softValues().buildAsync(loader);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void async_asyncLoader_weakKeys_evictionListener() {
    RemovalListener<Object, Object> evictionListener = (k, v, c) -> {};
    Caffeine.newBuilder().weakKeys().evictionListener(evictionListener).buildAsync(loader);
  }

  /* --------------- initialCapacity --------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void initialCapacity_negative() {
    Caffeine.newBuilder().initialCapacity(-1);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void initialCapacity_twice() {
    Caffeine.newBuilder().initialCapacity(1).initialCapacity(1);
  }

  @Test
  public void initialCapacity_small() {
    // can't check, so just assert that it builds
    var builder = Caffeine.newBuilder().initialCapacity(0);
    assertThat(builder.initialCapacity).isEqualTo(0);
    builder.build();
  }

  @Test
  public void initialCapacity_large() {
    // don't build! just check that it configures
    var builder = Caffeine.newBuilder().initialCapacity(Integer.MAX_VALUE);
    assertThat(builder.initialCapacity).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- maximumSize --------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void maximumSize_negative() {
    Caffeine.newBuilder().maximumSize(-1);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void maximumSize_twice() {
    Caffeine.newBuilder().maximumSize(1).maximumSize(1);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void maximumSize_maximumWeight() {
    Caffeine.newBuilder().maximumWeight(1).maximumSize(1);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void maximumSize_weigher() {
    Caffeine.newBuilder().weigher(Weigher.singletonWeigher()).maximumSize(1);
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

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void maximumWeight_negative() {
    Caffeine.newBuilder().maximumWeight(-1);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void maximumWeight_twice() {
    Caffeine.newBuilder().maximumWeight(1).maximumWeight(1);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void maximumWeight_noWeigher() {
    Caffeine.newBuilder().maximumWeight(1).build();
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void maximumWeight_maximumSize() {
    Caffeine.newBuilder().maximumSize(1).maximumWeight(1);
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

  @Test(expectedExceptions = NullPointerException.class)
  public void weigher_null() {
    Caffeine.newBuilder().weigher(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void weigher_twice() {
    Caffeine.newBuilder().weigher(Weigher.singletonWeigher()).weigher(Weigher.singletonWeigher());
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void weigher_maximumSize() {
    Caffeine.newBuilder().maximumSize(1).weigher(Weigher.singletonWeigher());
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void weigher_noMaximumWeight() {
    Caffeine.newBuilder().weigher(Weigher.singletonWeigher()).build();
  }

  @Test
  public void weigher() {
    Weigher<Object, Object> weigher = (k, v) -> 0;
    var builder = Caffeine.newBuilder().maximumWeight(0).weigher(weigher);
    assertThat(builder.weigher).isSameInstanceAs(weigher);
    builder.build();
  }

  /* --------------- expireAfterAccess --------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void expireAfterAccess_negative() {
    Caffeine.newBuilder().expireAfterAccess(-1, TimeUnit.MILLISECONDS);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfterAccess_expiry() {
    Caffeine.newBuilder().expireAfter(expiry).expireAfterAccess(1, TimeUnit.MILLISECONDS);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfterAccess_twice() {
    Caffeine.newBuilder().expireAfterAccess(1, TimeUnit.MILLISECONDS)
        .expireAfterAccess(1, TimeUnit.MILLISECONDS);
  }

  @Test
  public void expireAfterAccess_small() {
    var builder = Caffeine.newBuilder().expireAfterAccess(0, TimeUnit.MILLISECONDS);
    assertThat(builder.expireAfterAccessNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  public void expireAfterAccess_large() {
    var builder = Caffeine.newBuilder().expireAfterAccess(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterAccessNanos).isEqualTo(Integer.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterAccess().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- expireAfterAccess: java.time --------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void expireAfterAccess_duration_negative() {
    Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(-1));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfterAccess_duration_expiry() {
    Caffeine.newBuilder().expireAfter(expiry).expireAfterAccess(Duration.ofMillis(1));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfterAccess_duration_twice() {
    Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(1))
        .expireAfterAccess(Duration.ofMillis(1));
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

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void expireAfterWrite_negative() {
    Caffeine.newBuilder().expireAfterWrite(-1, TimeUnit.MILLISECONDS);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfterWrite_expiry() {
    Caffeine.newBuilder().expireAfter(expiry).expireAfterWrite(1, TimeUnit.MILLISECONDS);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfterWrite_twice() {
    Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MILLISECONDS)
        .expireAfterWrite(1, TimeUnit.MILLISECONDS);
  }

  @Test
  public void expireAfterWrite_small() {
    var builder = Caffeine.newBuilder().expireAfterWrite(0, TimeUnit.MILLISECONDS);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  public void expireAfterWrite_large() {
    var builder = Caffeine.newBuilder()
        .expireAfterWrite(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Integer.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Integer.MAX_VALUE);
  }

  /* --------------- expireAfterWrite: java.time --------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void expireAfterWrite_duration_negative() {
    Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(-1));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfterWrite_duration_expiry() {
    Caffeine.newBuilder().expireAfter(expiry).expireAfterWrite(Duration.ofMillis(1));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfterWrite_duration_twice() {
    Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(1))
        .expireAfterWrite(Duration.ofMillis(1));
  }

  @Test
  public void expireAfterWrite_duration() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1));
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Duration.ofMinutes(1).toNanos());
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  public void expireAfterWrite_duration_immediate() {
    var builder = Caffeine.newBuilder().expireAfterWrite(Duration.ZERO);
    assertThat(builder.expireAfterWriteNanos).isEqualTo(0);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS)).isEqualTo(0);
  }

  @Test
  public void expireAfterWrite_duration_excessive() {
    var builder = Caffeine.newBuilder().expireAfterWrite(ChronoUnit.FOREVER.getDuration());
    assertThat(builder.expireAfterWriteNanos).isEqualTo(Long.MAX_VALUE);
    var expiration = builder.build().policy().expireAfterWrite().orElseThrow();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS)).isEqualTo(Long.MAX_VALUE);
  }

  /* --------------- expiry --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void expireAfter_null() {
    Caffeine.newBuilder().expireAfter(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfter_twice() {
    Caffeine.newBuilder().expireAfter(expiry).expireAfter(expiry);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfter_access() {
    Caffeine.newBuilder().expireAfterAccess(1, TimeUnit.MILLISECONDS).expireAfter(expiry);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void expireAfter_write() {
    Caffeine.newBuilder().expireAfterWrite(1, TimeUnit.MILLISECONDS).expireAfter(expiry);
  }

  @Test
  public void expireAfter() {
    var builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThat(builder.expiry).isSameInstanceAs(expiry);
    builder.build();
  }

  /* --------------- refreshAfterWrite --------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void refreshAfterWrite_negative() {
    Caffeine.newBuilder().refreshAfterWrite(-1, TimeUnit.MILLISECONDS);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void refreshAfterWrite_twice() {
    Caffeine.newBuilder().refreshAfterWrite(1, TimeUnit.MILLISECONDS)
        .refreshAfterWrite(1, TimeUnit.MILLISECONDS);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void refreshAfterWrite_noCacheLoader() {
    Caffeine.newBuilder().refreshAfterWrite(1, TimeUnit.MILLISECONDS).build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void refreshAfterWrite_zero() {
    Caffeine.newBuilder().refreshAfterWrite(0, TimeUnit.MILLISECONDS);
  }

  @Test
  public void refreshAfterWrite() {
    var builder = Caffeine.newBuilder()
        .refreshAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(1));
    builder.build(k -> k);
  }

  /* --------------- refreshAfterWrite: java.time --------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void refreshAfterWrite_duration_negative() {
    Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(-1));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void refreshAfterWrite_duration_twice() {
    Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(1))
        .refreshAfterWrite(Duration.ofMillis(1));
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void refreshAfterWrite_duration_noCacheLoader() {
    Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(1)).build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void refreshAfterWrite_duration_zero() {
    Caffeine.newBuilder().refreshAfterWrite(Duration.ZERO);
  }

  @Test
  public void refreshAfterWrite_duration() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(Duration.ofMinutes(1));
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(Duration.ofMinutes(1).toNanos());
    builder.build(k -> k);
  }

  @Test
  public void refreshAfterWrite_excessive() {
    var builder = Caffeine.newBuilder().refreshAfterWrite(ChronoUnit.FOREVER.getDuration());
    assertThat(builder.getRefreshAfterWriteNanos()).isEqualTo(Long.MAX_VALUE);
    builder.build(k -> k);
  }

  /* --------------- weakKeys --------------- */

  @Test(expectedExceptions = IllegalStateException.class)
  public void weakKeys_twice() {
    Caffeine.newBuilder().weakKeys().weakKeys();
  }

  @Test
  public void weakKeys() {
    Caffeine.newBuilder().weakKeys().build();
  }

  /* --------------- weakValues --------------- */

  @Test(expectedExceptions = IllegalStateException.class)
  public void weakValues_twice() {
    Caffeine.newBuilder().weakValues().weakValues();
  }

  @Test
  public void weakValues() {
    Caffeine.newBuilder().weakValues().build();
  }

  /* --------------- softValues --------------- */

  @Test(expectedExceptions = IllegalStateException.class)
  public void softValues_twice() {
    Caffeine.newBuilder().softValues().softValues();
  }

  @Test
  public void softValues() {
    Caffeine.newBuilder().softValues().build();
  }

  /* --------------- scheduler --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void scheduler_null() {
    Caffeine.newBuilder().scheduler(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void scheduler_twice() {
    Caffeine.newBuilder().scheduler(Scheduler.disabledScheduler())
        .scheduler(Scheduler.disabledScheduler());
  }

  @Test
  public void scheduler_system() {
    var builder = Caffeine.newBuilder().scheduler(Scheduler.systemScheduler());
    assertThat(builder.getScheduler()).isSameInstanceAs(Scheduler.systemScheduler());
    builder.build();
  }

  @Test
  public void scheduler_custom() {
    Scheduler scheduler = (executor, task, delay, unit) -> DisabledFuture.INSTANCE;
    var builder = Caffeine.newBuilder().scheduler(scheduler);
    assertThat(((GuardedScheduler) builder.getScheduler()).delegate).isSameInstanceAs(scheduler);
    builder.build();
  }

  /* --------------- executor --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void executor_null() {
    Caffeine.newBuilder().executor(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void executor_twice() {
    Caffeine.newBuilder().executor(directExecutor())
        .executor(directExecutor());
  }

  @Test
  public void executor() {
    var builder = Caffeine.newBuilder().executor(directExecutor());
    assertThat(builder.getExecutor()).isSameInstanceAs(directExecutor());
    builder.build();
  }

  /* --------------- ticker --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void ticker_null() {
    Caffeine.newBuilder().ticker(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void ticker_twice() {
    Caffeine.newBuilder().ticker(Ticker.systemTicker()).ticker(Ticker.systemTicker());
  }

  @Test
  public void ticker() {
    Ticker ticker = new FakeTicker()::read;
    var builder = Caffeine.newBuilder().ticker(ticker);
    assertThat(builder.ticker).isSameInstanceAs(ticker);
    builder.build();
  }

  /* --------------- stats --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void recordStats_null() {
    Caffeine.newBuilder().recordStats(null);
  }

  @Test
  public void recordStats_twice() {
    Supplier<StatsCounter> supplier = () -> statsCounter;
    Runnable[] tasks = {
        () -> Caffeine.newBuilder().recordStats().recordStats(),
        () -> Caffeine.newBuilder().recordStats(supplier).recordStats(),
        () -> Caffeine.newBuilder().recordStats().recordStats(supplier),
        () -> Caffeine.newBuilder().recordStats(supplier).recordStats(supplier),
    };
    for (Runnable task : tasks) {
      try {
        task.run();
        Assert.fail();
      } catch (IllegalStateException expected) {}
    }
  }

  @Test
  public void recordStats() {
    var builder = Caffeine.newBuilder().recordStats();
    assertThat(builder.statsCounterSupplier).isEqualTo(Caffeine.ENABLED_STATS_COUNTER_SUPPLIER);
    builder.build();
  }

  @Test
  public void recordStats_custom() {
    Supplier<StatsCounter> supplier = () -> statsCounter;
    var builder = Caffeine.newBuilder().recordStats(supplier);
    builder.statsCounterSupplier.get().recordEviction(1, RemovalCause.SIZE);
    verify(statsCounter).recordEviction(1, RemovalCause.SIZE);
    builder.build();
  }

  /* --------------- removalListener --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void removalListener_null() {
    Caffeine.newBuilder().removalListener(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void removalListener_twice() {
    Caffeine.newBuilder().removalListener((k, v, c) -> {}).removalListener((k, v, c) -> {});
  }

  @Test
  public void removalListener() {
    RemovalListener<Object, Object> removalListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().removalListener(removalListener);
    assertThat(builder.getRemovalListener(false)).isSameInstanceAs(removalListener);
    builder.build();
  }

  /* --------------- removalListener --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void evictionListener_null() {
    Caffeine.newBuilder().evictionListener(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void evictionListener_twice() {
    Caffeine.newBuilder().evictionListener((k, v, c) -> {}).evictionListener((k, v, c) -> {});
  }

  @Test
  public void evictionListener() {
    RemovalListener<Object, Object> removalListener = (k, v, c) -> {};
    var builder = Caffeine.newBuilder().evictionListener(removalListener);
    assertThat(builder.evictionListener).isSameInstanceAs(removalListener);
    builder.build();
  }
}
