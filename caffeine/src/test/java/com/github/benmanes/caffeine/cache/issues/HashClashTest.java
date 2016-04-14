/*
 * Copyright 2016 Branimir Lambov. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.issues;

import java.util.Iterator;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;

/**
 * CASSANDRA-11452: Hash collisions cause the cache to not admit new entries.
 *
 * @author Branimir Lambov (github.com/blambov)
 */
@Test(groups = "isolated", singleThreaded = true)
public final class HashClashTest {
  private static final Long LONG_1 = 1L;
  private static final long ITERS = 200_000;

  @Test
  public void testGuava() throws Exception {
    testCache(new GuavaCache(150));
  }

  @Test
  public void testCaffeine() throws Exception {
    testCache(new CaffeineCache(150));
  }

  private void testCache(ICache cache) throws Exception {
    long startTime = System.currentTimeMillis();
    long i = 200000;
    int STEP = 5;

    for (long j = 0; j < 300; ++j) {
      get(cache, 1L);
      get(cache, j);
    }
    System.out.format("%s size %,d requests %,d hit ratio %f%n",
        cache.toString(), cache.size(), cache.reqCount(), cache.hitRate());
    System.out.println(ImmutableList.copyOf(cache.keyIterator()));

    // add a hashcode clash for 1
    {
      Long CLASH = (i << 32) ^ i ^ 1;
      assert CLASH.hashCode() == LONG_1.hashCode();
      get(cache, CLASH);
      System.out.println(ImmutableList.copyOf(cache.keyIterator()));
    }

    // repeat some entries to let CLASH flow to the probation head
    for (long j = 0; j < 300; ++j) {
      get(cache, 1L);
      get(cache, j);
    }
    System.out.println(ImmutableList.copyOf(cache.keyIterator()));

    // Now run a repeating sequence which has a longer length than eden space size.
    for (i = 0; i < ITERS; i += STEP) {
      get(cache, 1L);
      for (long j = 0; j < STEP; ++j) {
        get(cache, -j);
      }
    }
    System.out.println(ImmutableList.copyOf(cache.keyIterator()));

    long endTime = System.currentTimeMillis();
    System.out.println();

    System.out.format("%s size %,d requests %,d hit ratio %f%n",
        cache.getClass().getSimpleName(), cache.size(), cache.reqCount(), cache.hitRate());
    System.out.format("%,d lookups completed in %,d ms%n%n", ITERS, endTime - startTime);

    cache.clear();
  }

  public Long get(ICache cache, Long key) {
    Long d = cache.get(key);
    if (d == null) {
      d = load(key);
      cache.put(key, d);
    }
    return d;
  }

  public Long load(Long key) {
    return key;
  }

  interface ICache {
    public int size();

    public void put(Long key, Long value);

    public Long get(Long key);

    public void clear();

    public Iterator<Long> keyIterator();

    public long reqCount();

    public double hitRate();
  }

  static final class GuavaCache implements ICache {
    final Cache<Long, Long> cache;

    public GuavaCache(long size) {
      cache = CacheBuilder.newBuilder().maximumSize(size).recordStats().build();
    }

    @Override
    public int size() {
      return (int) cache.size();
    }

    @Override
    public void put(Long key, Long value) {
      cache.put(key, value);
    }

    @Override
    public Long get(Long key) {
      return cache.getIfPresent(key);
    }

    @Override
    public void clear() {
      cache.invalidateAll();
    }

    @Override
    public Iterator<Long> keyIterator() {
      return cache.asMap().keySet().iterator();
    }

    @Override
    public long reqCount() {
      return cache.stats().requestCount();
    }

    @Override
    public double hitRate() {
      return cache.stats().hitRate();
    }
  }

  static final class CaffeineCache implements ICache {
    final com.github.benmanes.caffeine.cache.Cache<Long, Long> cache;

    public CaffeineCache(long size) {
      cache = Caffeine.newBuilder()
          .executor(Runnable::run)
          .maximumSize(size)
          .recordStats()
          .build();
    }

    @Override
    public int size() {
      return cache.asMap().size();
    }

    @Override
    public void put(Long key, Long value) {
      cache.put(key, value);
    }

    @Override
    public Long get(Long key) {
      return cache.getIfPresent(key);
    }

    @Override
    public void clear() {
      cache.invalidateAll();
    }

    @Override
    public Iterator<Long> keyIterator() {
      return cache.policy().eviction().get().hottest(10000000).keySet().iterator();
    }

    @Override
    public long reqCount() {
      return cache.stats().requestCount();
    }

    @Override
    public double hitRate() {
      return cache.stats().hitRate();
    }
  }
}
