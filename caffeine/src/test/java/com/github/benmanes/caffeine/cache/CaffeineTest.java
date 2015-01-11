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

import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Advanced.Eviction;
import com.github.benmanes.caffeine.cache.Advanced.Expiration;
import com.github.benmanes.caffeine.cache.testing.FakeTicker;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * A test for the builder methods.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineTest {
  final CacheLoader<Object, Object> single = key -> key;

  @Test
  public void unconfigured() {
    assertThat(Caffeine.newBuilder().build(), is(not(nullValue())));
    assertThat(Caffeine.newBuilder().build(single), is(not(nullValue())));
    assertThat(Caffeine.newBuilder().buildAsync(single), is(not(nullValue())));
    assertThat(Caffeine.newBuilder().toString(), is(Caffeine.newBuilder().toString()));
  }

  @Test
  public void configured() {
    Caffeine<Object, Object> configured = Caffeine.newBuilder()
        .initialCapacity(1).maximumSize(1).weakKeys().softValues()
        .expireAfterAccess(1, TimeUnit.SECONDS).expireAfterWrite(1, TimeUnit.SECONDS)
        .removalListener(x -> {}).recordStats();
    assertThat(configured.build(), is(not(nullValue())));
    assertThat(configured.build(single), is(not(nullValue())));
    assertThat(Caffeine.newBuilder().buildAsync(single), is(not(nullValue())));
    assertThat(configured.toString(), is(not(Caffeine.newBuilder().toString())));
  }

  /* ---------------- initialCapacity -------------- */

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

  /* ---------------- maximumSize -------------- */

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
    Caffeine.newBuilder().weigher(Weigher.singleton()).maximumSize(1);
  }

  @Test
  public void maximumSize_small() {
    Caffeine<?, ?> builder = Caffeine.newBuilder().maximumSize(0);
    assertThat(builder.maximumSize, is(0L));
    Cache<?, ?> cache = builder.build();
    assertThat(cache.advanced().eviction().get().getMaximumSize(), is(0L));
  }

  @Test
  public void maximumSize_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder().maximumSize(Integer.MAX_VALUE);
    assertThat(builder.maximumSize, is((long) Integer.MAX_VALUE));
    Cache<?, ?> cache = builder.build();
    assertThat(cache.advanced().eviction().get().getMaximumSize(), is((long) Integer.MAX_VALUE));
  }

  /* ---------------- maximumWeight -------------- */

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
    Caffeine<?, ?> builder = Caffeine.newBuilder().maximumWeight(0).weigher(Weigher.singleton());
    assertThat(builder.weigher, is(Weigher.singleton()));
    assertThat(builder.maximumWeight, is(0L));
    Eviction<?, ?> eviction = builder.build().advanced().eviction().get();
    assertThat(eviction.getMaximumSize(), is(0L));
    assertThat(eviction.isWeighted(), is(true));
  }

  @Test
  public void maximumWeight_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .maximumWeight(Integer.MAX_VALUE).weigher(Weigher.singleton());
    assertThat(builder.maximumWeight, is((long) Integer.MAX_VALUE));
    assertThat(builder.weigher, is(Weigher.singleton()));

    Eviction<?, ?> eviction = builder.build().advanced().eviction().get();
    assertThat(eviction.getMaximumSize(), is((long) Integer.MAX_VALUE));
    assertThat(eviction.isWeighted(), is(true));
  }

  /* ---------------- weigher -------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void weigher_null() {
    Caffeine.newBuilder().weigher(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void weigher_twice() {
    Caffeine.newBuilder().weigher(Weigher.singleton()).weigher(Weigher.singleton());
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void weigher_maximumSize() {
    Caffeine.newBuilder().maximumSize(1).weigher(Weigher.singleton());
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void weigher_noMaximumWeight() {
    Caffeine.newBuilder().weigher(Weigher.singleton()).build();
  }

  @Test
  public void weigher() {
    Weigher<Object, Object> weigher = (k, v) -> 0;
    Caffeine<?, ?> builder = Caffeine.newBuilder().maximumWeight(0).weigher(weigher);
    assertThat(builder.weigher, is(sameInstance(weigher)));
    builder.build();
  }

  /* ---------------- expireAfterAccess -------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void expireAfterAccess_negative() {
    Caffeine.newBuilder().expireAfterAccess(-1, TimeUnit.MILLISECONDS);
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
    Expiration<?, ?> expiration = builder.build().advanced().expireAfterAccess().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS), is(0L));
  }

  @Test
  public void expireAfterAccess_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .expireAfterAccess(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterAccessNanos, is((long) Integer.MAX_VALUE));
    Expiration<?, ?> expiration = builder.build().advanced().expireAfterAccess().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS), is((long) Integer.MAX_VALUE));
  }

  /* ---------------- expireAfterWrite -------------- */

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void expireAfterWrite_negative() {
    Caffeine.newBuilder().expireAfterWrite(-1, TimeUnit.MILLISECONDS);
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
    Expiration<?, ?> expiration = builder.build().advanced().expireAfterWrite().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.MILLISECONDS), is(0L));
  }

  @Test
  public void expireAfterWrite_large() {
    Caffeine<?, ?> builder = Caffeine.newBuilder()
        .expireAfterWrite(Integer.MAX_VALUE, TimeUnit.NANOSECONDS);
    assertThat(builder.expireAfterWriteNanos, is((long) Integer.MAX_VALUE));
    Expiration<?, ?> expiration = builder.build().advanced().expireAfterWrite().get();
    assertThat(expiration.getExpiresAfter(TimeUnit.NANOSECONDS), is((long) Integer.MAX_VALUE));
  }

  /* ---------------- refreshAfterWrite -------------- */

  @Test(expectedExceptions = IllegalStateException.class)
  public void refreshAfterWrite_twice() {
    Caffeine.newBuilder().refreshAfterWrite(1, TimeUnit.MILLISECONDS)
        .refreshAfterWrite(1, TimeUnit.MILLISECONDS);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void refreshAfterWrite_noCacheLoader() {
    Caffeine.newBuilder().refreshAfterWrite(1, TimeUnit.MILLISECONDS).build();
  }

  @Test
  public void refreshAfterWrite() {
    Caffeine<Object, Object> builder = Caffeine.newBuilder()
        .refreshAfterWrite(1, TimeUnit.MILLISECONDS);
    assertThat(builder.getRefreshNanos(), is(TimeUnit.MILLISECONDS.toNanos(1)));
    builder.build(k -> k);
  }

  /* ---------------- weakKeys -------------- */

  @Test(expectedExceptions = IllegalStateException.class)
  public void weakKeys_twice() {
    Caffeine.newBuilder().weakKeys().weakKeys();
  }

  @Test
  public void weakKeys() {
    Caffeine.newBuilder().weakKeys().build();
  }

  /* ---------------- weakValues -------------- */

  @Test(expectedExceptions = IllegalStateException.class)
  public void weakValues_twice() {
    Caffeine.newBuilder().weakValues().weakValues();
  }

  @Test
  public void weakValues() {
    Caffeine.newBuilder().weakValues().build();
  }

  /* ---------------- softValues -------------- */

  @Test(expectedExceptions = IllegalStateException.class)
  public void softValues_twice() {
    Caffeine.newBuilder().softValues().softValues();
  }

  @Test
  public void softValues() {
    Caffeine.newBuilder().softValues().build();
  }

  /* ---------------- executor -------------- */

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

  /* ---------------- ticker -------------- */

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
    Ticker ticker = new FakeTicker();
    Caffeine<?, ?> builder = Caffeine.newBuilder().ticker(ticker);
    assertThat(builder.getTicker(), is(ticker));
    builder.build();
  }

  /* ---------------- removalListener -------------- */

  @Test(expectedExceptions = NullPointerException.class)
  public void removalListener_null() {
    Caffeine.newBuilder().removalListener(null);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void removalListener_twice() {
    Caffeine.newBuilder().removalListener(notif -> {}).removalListener(notif -> {});
  }

  @Test
  public void removalListener() {
    RemovalListener<Object, Object> removalListener = notif -> {};
    Caffeine<?, ?> builder = Caffeine.newBuilder().removalListener(removalListener);
    assertThat(builder.getRemovalListener(), is(removalListener));
    builder.build();
  }
}
