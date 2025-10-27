/*
 * Copyright 2017 Yonik Seeley. All Rights Reserved.
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

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.Locale.US;

import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.benmanes.caffeine.testing.ConcurrentTestHarness;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.Var;

/**
 * SOLR-10141: Removal listener notified with stale value
 * <p>
 * When an entry is chosen for eviction and concurrently updated, the value notified should be the
 * updated one if the <code>put</code> was successful.
 *
 * @author yseeley@gmail.com (Yonik Seeley)
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(groups = "isolated")
public final class Solr10141Test {
  static final int blocksInTest = 400;
  static final int maxEntries = blocksInTest / 2;

  static final int nThreads = 64;
  static final int nReads = 10_000_000;
  static final int readsPerThread = nReads / nThreads;

  // odds (1 in N) of the next block operation being on the same block as the previous operation...
  // helps flush concurrency issues
  static final int readLastBlockOdds = 10;
  // sometimes insert a new entry for the key even if one was found
  static final boolean updateAnyway = true;

  final Random rnd = new Random();

  @Test
  public void eviction() {
    var hits = new AtomicLong();
    var inserts = new AtomicLong();
    var removals = new AtomicLong();

    RemovalListener<Long, Val> listener = (k, v, removalCause) -> {
      assertThat(v).isNotNull();
      assertThat(v.key).isEqualTo(k);
      if (!v.live.compareAndSet(/* expectedValue= */ true, /* newValue= */ false)) {
        throw new RuntimeException(String.format(US,
            "listener called more than once! k=%s, v=%s, removalCause=%s", k, v, removalCause));
      }
      removals.incrementAndGet();
    };

    Cache<Long, Val> cache = Caffeine.newBuilder()
        .executor(ConcurrentTestHarness.executor)
        .removalListener(listener)
        .maximumSize(maxEntries)
        .build();
    var lastBlock = new AtomicLong();
    var maxObservedSize = new AtomicLong();
    var failed = new ConcurrentLinkedQueue<Throwable>();
    ConcurrentTestHarness.timeTasks(nThreads, new Runnable() {
      @Override public void run() {
        try {
          var r = new Random(rnd.nextLong());
          for (int i = 0; i < readsPerThread; i++) {
            test(r);
          }
        } catch (Throwable e) {
          failed.add(e);
        }
      }

      void test(Random r) {
        @Var long block = r.nextInt(blocksInTest);
        if (readLastBlockOdds > 0 && r.nextInt(readLastBlockOdds) == 0) {
          // some percent of the time, try to read the last block another
          block = lastBlock.get();
        }
        // thread was just reading/writing
        lastBlock.set(block);

        long k = block;
        @Var Val v = cache.getIfPresent(k);
        if (v != null) {
          hits.incrementAndGet();
          assertThat(k).isEqualTo(v.key);
        }

        if ((v == null) || (updateAnyway && r.nextBoolean())) {
          v = new Val(k);
          cache.put(k, v);
          inserts.incrementAndGet();
        }

        long sz = cache.estimatedSize();
        if (sz > maxObservedSize.get()) {
          // race condition here, but an estimate is OK
          maxObservedSize.set(sz);
        }
      }
    });

    await().until(() -> (inserts.get() - removals.get()) == cache.estimatedSize());

    var message = String.format(US,
        "entries=%,d inserts=%,d removals=%,d hits=%,d maxEntries=%,d maxObservedSize=%,d%n",
        cache.estimatedSize(), inserts.get(), removals.get(), hits.get(), maxEntries,
        maxObservedSize.get());
    assertWithMessage(message).that(failed).isEmpty();
  }

  @Test
  public void clear() {
    var inserts = new AtomicLong();
    var removals = new AtomicLong();
    var failed = new ConcurrentLinkedQueue<Throwable>();

    RemovalListener<Long, Val> listener = (k, v, removalCause) -> {
      assertThat(v).isNotNull();
      assertThat(v.key).isEqualTo(k);
      if (!v.live.compareAndSet(/* expectedValue= */ true, /* newValue= */ false)) {
        throw new RuntimeException(String.format(US,
            "listener called more than once! k=%s, v=%s, removalCause=%s", k, v, removalCause));
      }
      removals.incrementAndGet();
    };

    Cache<Long, Val> cache = Caffeine.newBuilder()
        .maximumSize(Integer.MAX_VALUE)
        .removalListener(listener)
        .build();
    ConcurrentTestHarness.timeTasks(nThreads, new Runnable() {
      @Override public void run() {
        try {
          var r = new Random(rnd.nextLong());
          for (int i = 0; i < readsPerThread; i++) {
            test(r);
          }
        } catch (Throwable e) {
          failed.add(e);
        }
      }
      void test(Random r) {
        long k = r.nextInt(blocksInTest);
        @Var Val v = cache.getIfPresent(k);
        if (v != null) {
          assertThat(k).isEqualTo(v.key);
        }

        if ((v == null) || (updateAnyway && r.nextBoolean())) {
          v = new Val(k);
          cache.put(k, v);
          inserts.incrementAndGet();
        }

        if (r.nextInt(10) == 0) {
          cache.asMap().clear();
        }
      }
    });

    cache.asMap().clear();
    await().until(() -> inserts.get() == removals.get());
    assertThat(failed).isEmpty();
  }

  static final class Val {
    final AtomicBoolean live;
    final long key;

    Val(long key) {
      this.live = new AtomicBoolean(true);
      this.key = key;
    }
    @Override public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("live", live.get())
          .toString();
    }
  }
}
