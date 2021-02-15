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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.Policy.FixedExpiration;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.google.common.testing.FakeTicker;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * A test for the builder methods.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings({"PreferJavaTimeOverload", "deprecation", "CheckReturnValue"})
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
  public void unconfigured() {
    assertThat(Caffeine.newBuilder().build(), is(not(nullValue())));
    assertThat(Caffeine.newBuilder().build(loader), is(not(nullValue())));
    assertThat(Caffeine.newBuilder().buildAsync(), is(not(nullValue())));
    assertThat(Caffeine.newBuilder().buildAsync(loader), is(not(nullValue())));
    assertThat(Caffeine.newBuilder().toString(), is(Caffeine.newBuilder().toString()));
  }

  @Test
  public void configured() {
    Caffeine<Object, Object> configured = Caffeine.newBuilder()
        .initialCapacity(1).weakKeys()
        .expireAfterAccess(1, TimeUnit.SECONDS).expireAfterWrite(1, TimeUnit.SECONDS)
        .removalListener((k, v, c) -> {}).recordStats();
    assertThat(configured.build(), is(not(nullValue())));
    assertThat(configured.buildAsync(), is(not(nullValue())));
    assertThat(configured.build(loader), is(not(nullValue())));
    assertThat(configured.buildAsync(loader), is(not(nullValue())));

    assertThat(configured.refreshAfterWrite(1, TimeUnit.SECONDS).toString(),
        is(not(Caffeine.newBuilder().toString())));
    assertThat(Caffeine.newBuilder().maximumSize(1).toString(),
        is(not(Caffeine.newBuilder().maximumWeight(1).toString())));
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
    assertThat(Caffeine.from(CaffeineSpec.parse("")), is(not(nullValue())));
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
    assertThat(Caffeine.from(""), is(not(nullValue())));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().initialCapacity(0);
    assertThat(builder.initialCapacity, is(0));
    builder.build();
  }

  @Test
  public void initialCapacity_large() {
    // don't build! just check that it configures
    Caffeine<?, ?> builder = Caffeine.newBuilder().initialCapacity(Integer.MAX_VALUE);
    assertThat(builder.initialCapacity, is(Integer.MAX_VALUE));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().maximumSize(0);
    assertThat(builder.maximumSize, is(0L));
    Cache<?, ?> cache = builder.build();
    assertThat(cache.policy().eviction().get().getMaximum(), is(0L));
  }

  @Test
  public void maximumSize_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder().maximumSize(Integer.MAX_VALUE);
    assertThat(builder.maximumSize, is((long) Integer.MAX_VALUE));
    Cache<?, ?> cache = builder.build();
    assertThat(cache.policy().eviction().get().getMaximum(), is((long) Integer.MAX_VALUE));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .maximumWeight(0).weigher(Weigher.singletonWeigher());
    assertThat(builder.weigher, is(Weigher.singletonWeigher()));
    assertThat(builder.maximumWeight, is(0L));
    Eviction<?, ?> eviction = builder.build().policy().eviction().get();
    assertThat(eviction.getMaximum(), is(0L));
    assertThat(eviction.isWeighted(), is(true));
  }

  @Test
  public void maximumWeight_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .maximumWeight(Integer.MAX_VALUE).weigher(Weigher.singletonWeigher());
    assertThat(builder.maximumWeight, is((long) Integer.MAX_VALUE));
    assertThat(builder.weigher, is(Weigher.singletonWeigher()));

    Eviction<?, ?> eviction = builder.build().policy().eviction().get();
    assertThat(eviction.getMaximum(), is((long) Integer.MAX_VALUE));
    assertThat(eviction.isWeighted(), is(true));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().maximumWeight(0).weigher(weigher);
    assertThat(builder.weigher, is(sameInstance(weigher)));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().expireAfterAccess(0, TimeUnit.MILLISECONDS);
    assertThat(builder.expireAfterAccessNanos, is(0L));
    FixedExpiration<?, ?> expiration = builder.build().policy().expireAfterAccess().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS), is(0L));
  }

  @Test
  public void expireAfterAccess_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .expireAfterAccess(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterAccessNanos, is((long) Integer.MAX_VALUE));
    FixedExpiration<?, ?> expiration = builder.build().policy().expireAfterAccess().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS), is((long) Integer.MAX_VALUE));
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
  public void expireAfterAccess_duration_small() {
    Caffeine<?, ?> builder = Caffeine.newBuilder().expireAfterAccess(Duration.ofMillis(0));
    assertThat(builder.expireAfterAccessNanos, is(0L));
    FixedExpiration<?, ?> expiration = builder.build().policy().expireAfterAccess().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS), is(0L));
  }

  @Test
  public void expireAfterAccess_duration_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofNanos(Integer.MAX_VALUE));
    assertThat(builder.expireAfterAccessNanos, is((long) Integer.MAX_VALUE));
    FixedExpiration<?, ?> expiration = builder.build().policy().expireAfterAccess().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS), is((long) Integer.MAX_VALUE));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().expireAfterWrite(0, TimeUnit.MILLISECONDS);
    assertThat(builder.expireAfterWriteNanos, is(0L));
    FixedExpiration<?, ?> expiration = builder.build().policy().expireAfterWrite().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS), is(0L));
  }

  @Test
  public void expireAfterWrite_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .expireAfterWrite(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterWriteNanos, is((long) Integer.MAX_VALUE));
    FixedExpiration<?, ?> expiration = builder.build().policy().expireAfterWrite().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS), is((long) Integer.MAX_VALUE));
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
  public void expireAfterWrite_duration_small() {
    Caffeine<?, ?> builder = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(0));
    assertThat(builder.expireAfterWriteNanos, is(0L));
    FixedExpiration<?, ?> expiration = builder.build().policy().expireAfterWrite().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS), is(0L));
  }

  @Test
  public void expireAfterWrite_duration_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofNanos(Integer.MAX_VALUE));
    assertThat(builder.expireAfterWriteNanos, is((long) Integer.MAX_VALUE));
    FixedExpiration<?, ?> expiration = builder.build().policy().expireAfterWrite().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS), is((long) Integer.MAX_VALUE));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().expireAfter(expiry);
    assertThat(builder.expiry, is(expiry));
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
    Caffeine<Object, Object> builder = Caffeine.newBuilder()
        .refreshAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThat(builder.getRefreshAfterWriteNanos(), is(TimeUnit.MILLISECONDS.toNanos(1)));
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
    Caffeine.newBuilder().refreshAfterWrite(Duration.ofMillis(0));
  }

  @Test
  public void refreshAfterWrite_duration() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder()
        .refreshAfterWrite(Duration.ofMillis(1));
    assertThat(builder.getRefreshAfterWriteNanos(), is(TimeUnit.MILLISECONDS.toNanos(1)));
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
  public void scheduler() {
    Scheduler scheduler = (executor, task, delay, unit) -> DisabledFuture.INSTANCE;
    Caffeine<?, ?> builder = Caffeine.newBuilder().scheduler(scheduler);
    assertThat(((GuardedScheduler) builder.getScheduler()).delegate, is(scheduler));
    builder.build();
  }

  /* --------------- executor --------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void executor_null() {
    Caffeine.newBuilder().executor(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void executor_twice() {
    Caffeine.newBuilder().executor(MoreExecutors.directExecutor())
        .executor(MoreExecutors.directExecutor());
  }

  @Test
  public void executor() {
    Caffeine<?, ?> builder = Caffeine.newBuilder().executor(MoreExecutors.directExecutor());
    assertThat(builder.getExecutor(), is(MoreExecutors.directExecutor()));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().ticker(ticker);
    assertThat(builder.ticker, is(ticker));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().recordStats();
    assertThat(builder.statsCounterSupplier, is(Caffeine.ENABLED_STATS_COUNTER_SUPPLIER));
    builder.build();
  }

  @Test
  public void recordStats_custom() {
    Supplier<StatsCounter> supplier = () -> statsCounter;
    Caffeine<?, ?> builder = Caffeine.newBuilder().recordStats(supplier);
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().removalListener(removalListener);
    assertThat(builder.getRemovalListener(false), is(removalListener));
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
    Caffeine<?, ?> builder = Caffeine.newBuilder().evictionListener(removalListener);
    assertThat(builder.evictionListener, is(removalListener));
    builder.build();
  }
}
