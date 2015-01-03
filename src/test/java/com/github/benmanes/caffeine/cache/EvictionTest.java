/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.matchers.IsEmptyMap.emptyMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Advanced.Eviction;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.RejectingRemovalListener;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * The test cases for caches with a page replacement algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class EvictionTest {
  // TODO
  public void isWeighted() {}
  public void weightedSize() {}
  public void evict_weighted() {}

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL,
      removalListener = Listener.REJECTING)
  public void removalListener_fails(Cache<Integer, Integer> cache, CacheContext context) {
    RejectingRemovalListener<Integer, Integer> removalListener = context.removalListener();
    // Guava-style caches reject before the max size is reached & are unpredictable
    removalListener.rejected = 0;
    long size = cache.size();
    for (Integer key : context.absentKeys()) {
      cache.put(key, -key);
      if (cache.size() != ++size) {
        break;
      }
    }
    assertThat(removalListener.rejected, is(1));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      maximumSize = { MaximumSize.ZERO, MaximumSize.ONE, MaximumSize.FULL })
  public void evict(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    for (Integer key : context.absentKeys()) {
      cache.put(key, -key);
    }
    if (eviction.isWeighted()) {
      assertThat(eviction.weightedSize().get(), is(context.maximumWeight()));
    } else {
      assertThat(cache.size(), is(context.maximumSize()));
    }
    assertThat(cache, hasRemovalNotifications(
        context, context.absentKeys().size(), RemovalCause.SIZE));
  }

  /* ---------------- Advanced: MaximumSize -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void maximumSize_decrease(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    long newSize = context.maximumWeightOrSize() / 2;
    eviction.setMaximumSize(newSize);
    assertThat(eviction.getMaximumSize(), is(newSize));
    if (context.initialSize() > newSize) {
      assertThat(cache.size(), is(newSize));
      assertThat(cache, hasRemovalNotifications(context, newSize, RemovalCause.SIZE));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void maximumSize_decrease_min(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximumSize(0);
    assertThat(eviction.getMaximumSize(), is(0L));
    if (context.initialSize() > 0) {
      assertThat(cache.size(), is(0L));
    }
    assertThat(cache, hasRemovalNotifications(context, context.initialSize(), RemovalCause.SIZE));
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void maximumSize_decrease_negative(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    try {
      eviction.setMaximumSize(-1);
    } finally {
      assertThat(eviction.getMaximumSize(), is(context.maximumWeightOrSize()));
      assertThat(cache, hasRemovalNotifications(context, 0, RemovalCause.SIZE));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void maximumSize_increase(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximumSize(2 * context.maximumWeightOrSize());
    assertThat(cache.size(), is(context.initialSize()));
    assertThat(eviction.getMaximumSize(), is(2 * context.maximumWeightOrSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = MaximumSize.FULL, removalListener = Listener.REJECTING)
  public void maximumSize_increase_max(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximumSize(Long.MAX_VALUE);
    assertThat(cache.size(), is(context.initialSize()));
    assertThat(eviction.getMaximumSize(), is(Long.MAX_VALUE - Integer.MAX_VALUE)); // impl detail
  }

  /* ---------------- Advanced: Coldest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void coldest_unmodifiable(Eviction<Integer, Integer> eviction) {
    eviction.coldest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void coldest_negative(Eviction<Integer, Integer> eviction) {
    eviction.coldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void coldest_zero(Eviction<Integer, Integer> eviction) {
    assertThat(eviction.coldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void coldest_partial(CacheContext context, Eviction<Integer, Integer> eviction) {
    int count = (int) context.initialSize() / 2;
    assertThat(eviction.coldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void coldest_order(CacheContext context, Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> coldest = eviction.coldest(Integer.MAX_VALUE);
    assertThat(Iterables.elementsEqual(coldest.keySet(), context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void coldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> coldest = eviction.coldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(coldest, is(equalTo(context.original())));
  }

  /* ---------------- Advanced: Hottest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void hottest_unmodifiable(Eviction<Integer, Integer> eviction) {
    eviction.hottest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void hottest_negative(Eviction<Integer, Integer> eviction) {
    eviction.hottest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void hottest_zero(Eviction<Integer, Integer> eviction) {
    assertThat(eviction.hottest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL)
  public void hottest_partial(CacheContext context, Eviction<Integer, Integer> eviction) {
    int count = (int) context.initialSize() / 2;
    assertThat(eviction.hottest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      population = Population.FULL, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hottest_order(CacheContext context, Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> hottest = eviction.hottest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(hottest.keySet()).reverse());
    assertThat(Iterables.elementsEqual(keys, context.original().keySet()), is(true));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void hottest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> hottest = eviction.hottest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(hottest, is(equalTo(context.original())));
  }
}
