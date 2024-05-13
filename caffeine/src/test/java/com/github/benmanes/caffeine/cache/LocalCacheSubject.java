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

import static com.github.benmanes.caffeine.cache.LinkedDequeSubject.deque;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.MapSubject.map;
import static com.google.common.truth.Truth.assertThat;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Async.AsyncWeigher;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedLocalAsyncCache;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedLocalAsyncLoadingCache;
import com.github.benmanes.caffeine.cache.BoundedLocalCache.BoundedLocalManualCache;
import com.github.benmanes.caffeine.cache.LocalAsyncLoadingCache.LoadingCacheView;
import com.github.benmanes.caffeine.cache.References.WeakKeyEqualsReference;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.github.benmanes.caffeine.cache.TimerWheel.Sentinel;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalAsyncCache;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalAsyncLoadingCache;
import com.github.benmanes.caffeine.cache.UnboundedLocalCache.UnboundedLocalManualCache;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.github.benmanes.caffeine.cache.testing.Weighers;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Sets;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Propositions for {@link LocalCache}-based subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("GuardedBy")
public final class LocalCacheSubject extends Subject {
  private final Object actual;

  private LocalCacheSubject(FailureMetadata metadata, Object subject) {
    super(metadata, subject);
    this.actual = subject;
  }

  public static Factory<LocalCacheSubject, AsyncCache<?, ?>> asyncLocal() {
    return LocalCacheSubject::new;
  }

  public static Factory<LocalCacheSubject, Cache<?, ?>> syncLocal() {
    return LocalCacheSubject::new;
  }

  public static Factory<LocalCacheSubject, Map<?, ?>> mapLocal() {
    return LocalCacheSubject::new;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public void isValid() {
    if (actual instanceof BoundedLocalCache<?, ?>) {
      var bounded = (BoundedLocalCache<Object, Object>) actual;
      checkBounded(bounded);
    } else if (actual instanceof BoundedLocalManualCache<?, ?>) {
      var bounded = (BoundedLocalManualCache<Object, Object>) actual;
      checkBounded(bounded.cache);
    } else if (actual instanceof BoundedLocalAsyncCache<?, ?>) {
      var bounded = (BoundedLocalAsyncCache) actual;
      checkBounded(bounded.cache);
    } else if (actual instanceof UnboundedLocalCache<?, ?>) {
      var unbounded = (UnboundedLocalCache<?, ?>) actual;
      checkUnbounded(unbounded);
    } else if (actual instanceof UnboundedLocalAsyncCache<?, ?>) {
      var unbounded = (UnboundedLocalAsyncCache<?, ?>) actual;
      checkUnbounded(unbounded.cache);
    } else if (actual instanceof UnboundedLocalManualCache<?, ?>) {
      var unbounded = (UnboundedLocalManualCache<?, ?>) actual;
      checkUnbounded(unbounded.cache);
    } else if (actual instanceof LoadingCacheView) {
      var async = ((LoadingCacheView<?, ?>) actual).asyncCache();
      if (async instanceof BoundedLocalAsyncLoadingCache<?, ?>) {
        var bounded = (BoundedLocalAsyncLoadingCache) async;
        checkBounded(bounded.cache);
      } else if (async instanceof UnboundedLocalAsyncLoadingCache<?, ?>) {
        var unbounded = (UnboundedLocalAsyncLoadingCache<?, ?>) async;
        checkUnbounded(unbounded.cache);
      }
    } else if (actual instanceof LocalAsyncCache.AsMapView<?, ?>) {
      var asMap = (LocalAsyncCache.AsMapView<?, ?>) actual;
      if (asMap.delegate instanceof BoundedLocalCache<?, ?>) {
        var bounded = (BoundedLocalCache) asMap.delegate;
        checkBounded(bounded);
      } else if (asMap.delegate instanceof UnboundedLocalCache<?, ?>) {
        var unbounded = (UnboundedLocalCache<?, ?>) asMap.delegate;
        checkUnbounded(unbounded);
      }
    }
  }

  private void checkStats(LocalCache<?, ?> cache) {
    if (cache.isRecordingStats()) {
      assertThat(cache.statsTicker()).isSameInstanceAs(Ticker.systemTicker());
      assertThat(cache.statsCounter()).isNotSameInstanceAs(StatsCounter.disabledStatsCounter());
    } else {
      assertThat(cache.statsTicker()).isSameInstanceAs(Ticker.disabledTicker());
      assertThat(cache.statsCounter()).isSameInstanceAs(StatsCounter.disabledStatsCounter());
    }
  }

  /* --------------- Bounded --------------- */

  private void checkBounded(BoundedLocalCache<Object, Object> bounded) {
    drain(bounded);
    checkReadBuffer(bounded);

    checkCache(bounded);
    checkStats(bounded);
    checkTimerWheel(bounded);
    checkEvictionDeque(bounded);
  }

  private void drain(BoundedLocalCache<Object, Object> bounded) {
    long adjustment = 0;
    for (;;) {
      bounded.cleanUp();

      if (!bounded.writeBuffer.isEmpty()) {
        continue; // additional writes to drain
      } else if (bounded.evicts() && (bounded.adjustment() != adjustment)) {
        adjustment = bounded.adjustment();
        continue; // finish climbing
      }
      break;
    }
  }

  private void checkReadBuffer(BoundedLocalCache<Object, Object> bounded) {
    if (!tryDrainBuffers(bounded)) {
      await().pollInSameThread().until(() -> tryDrainBuffers(bounded));
    }
  }

  private Boolean tryDrainBuffers(BoundedLocalCache<Object, Object> bounded) {
    bounded.cleanUp();
    var buffer = bounded.readBuffer;
    return (buffer.size() == 0) && (buffer.reads() == buffer.writes());
  }

  private void checkCache(BoundedLocalCache<Object, Object> bounded) {
    long remainingNanos = TimeUnit.SECONDS.toNanos(5);
    for (;;) {
      long end = System.nanoTime() + remainingNanos;
      try {
        if (bounded.evictionLock.tryLock(remainingNanos, TimeUnit.NANOSECONDS)) {
          bounded.evictionLock.unlock();
        } else {
          failWithActual("Maintenance lock cannot be acquired", bounded.evictionLock);
        }
        break;
      } catch (InterruptedException ignored) {
        remainingNanos = end - System.nanoTime();
      }
    }

    check("size").withMessage("cache.size() equal to data.size()")
        .that(bounded).hasSize(bounded.data.size());
    if (bounded.evicts()) {
      bounded.evictionLock.lock();
      try {
        check("weightedSize()").that(bounded.weightedSize()).isAtMost(bounded.maximum());
        check("windowWeightedSize()").that(bounded.windowWeightedSize())
            .isAtMost(bounded.windowMaximum());
        check("mainProtectedWeightedSize()").that(bounded.mainProtectedWeightedSize())
            .isAtMost(bounded.mainProtectedMaximum());
      } finally {
        bounded.evictionLock.unlock();
      }
    }

    if (bounded.isEmpty()) {
      check("bounded").about(map()).that(bounded).isExhaustivelyEmpty();
    }

    for (var node : bounded.data.values()) {
      checkNode(bounded, node);
    }
  }

  private void checkTimerWheel(BoundedLocalCache<Object, Object> bounded) {
    if (!bounded.expiresVariable()) {
      return;
    } else if (!doesTimerWheelMatch(bounded)) {
      await().pollInSameThread().until(() -> doesTimerWheelMatch(bounded));
    }

    var seen = Sets.newIdentityHashSet();
    for (int i = 0; i < bounded.timerWheel().wheel.length; i++) {
      for (int j = 0; j < bounded.timerWheel().wheel[i].length; j++) {
        var sentinel = bounded.timerWheel().wheel[i][j];
        check("first").that(sentinel).isInstanceOf(Sentinel.class);
        check("previousInVariableOrder")
            .that(sentinel.getPreviousInVariableOrder().getNextInVariableOrder())
            .isSameInstanceAs(sentinel);
        check("nextInVariableOrder")
            .that(sentinel.getNextInVariableOrder().getPreviousInVariableOrder())
            .isSameInstanceAs(sentinel);

        var node = sentinel.getNextInVariableOrder();
        while (node != sentinel) {
          var next = node.getNextInVariableOrder();
          var prev = node.getPreviousInVariableOrder();
          long duration = node.getVariableTime() - bounded.timerWheel().nanos;
          check("notExpired").that(duration).isGreaterThan(0);
          check("loopDetected").that(seen.add(node)).isTrue();
          check("wrongPrev").that(prev.getNextInVariableOrder()).isSameInstanceAs(node);
          check("wrongNext").that(next.getPreviousInVariableOrder()).isSameInstanceAs(node);
          node = node.getNextInVariableOrder();
        }
      }
    }
    check("cache.size() == timerWheel.size()").that(bounded).hasSize(seen.size());
  }

  private boolean doesTimerWheelMatch(BoundedLocalCache<Object, Object> bounded) {
    bounded.evictionLock.lock();
    try {
      var seen = Sets.newIdentityHashSet();
      for (int i = 0; i < bounded.timerWheel().wheel.length; i++) {
        for (int j = 0; j < bounded.timerWheel().wheel[i].length; j++) {
          var sentinel = bounded.timerWheel().wheel[i][j];
          var node = sentinel.getNextInVariableOrder();
          while (node != sentinel) {
            if (!seen.add(node)) {
              return false;
            }
            node = node.getNextInVariableOrder();
          }
        }
      }
      return (bounded.size() == seen.size());
    } finally {
      bounded.evictionLock.unlock();
    }
  }

  private void checkEvictionDeque(BoundedLocalCache<Object, Object> bounded) {
    if (bounded.evicts()) {
      long mainProbation = bounded.weightedSize()
          - bounded.windowWeightedSize() - bounded.mainProtectedWeightedSize();
      var deques = new ImmutableTable.Builder<String, Long, LinkedDeque<Node<Object, Object>>>()
          .put("window", bounded.windowWeightedSize(), bounded.accessOrderWindowDeque())
          .put("probation", mainProbation, bounded.accessOrderProbationDeque())
          .put("protected", bounded.mainProtectedWeightedSize(),
              bounded.accessOrderProtectedDeque())
          .build();
      checkLinks(bounded, deques);
      check("accessOrderWindowDeque()").about(deque())
          .that(bounded.accessOrderWindowDeque()).isValid();
      check("accessOrderProbationDeque()").about(deque())
          .that(bounded.accessOrderProbationDeque()).isValid();
    } else if (bounded.expiresAfterAccess()) {
      checkLinks(bounded, ImmutableTable.of("window",
          bounded.estimatedSize(), bounded.accessOrderWindowDeque()));
      check("accessOrderWindowDeque()").about(deque())
          .that(bounded.accessOrderWindowDeque()).isValid();
    }

    if (bounded.expiresAfterWrite()) {
      long expectedSize = bounded.evicts() ? bounded.weightedSize() : bounded.estimatedSize();
      checkLinks(bounded, ImmutableTable.of("writeOrder", expectedSize, bounded.writeOrderDeque()));
      check("writeOrderDeque()").about(deque())
          .that(bounded.writeOrderDeque()).isValid();
    }
  }

  private void checkLinks(BoundedLocalCache<Object, Object> bounded,
      ImmutableTable<String, Long, LinkedDeque<Node<Object, Object>>> deques) {
    if (!doLinksMatch(bounded, deques.values())) {
      await().pollInSameThread().until(() -> doLinksMatch(bounded, deques.values()));
    }

    int totalSize = 0;
    long totalWeightedSize = 0;
    Set<Node<Object, Object>> seen = Sets.newIdentityHashSet();
    for (var cell : deques.cellSet()) {
      long weightedSize = scanLinks(bounded, cell.getValue(), seen);
      check(cell.getRowKey()).that(weightedSize).isEqualTo(cell.getColumnKey());
      totalSize += cell.getValue().size();
      totalWeightedSize += weightedSize;
    }
    check("linkSize").withMessage("cache.size() != links").that(bounded).hasSize(seen.size());
    check("totalSize").withMessage("cache.size() == deque.size()").that(bounded).hasSize(totalSize);

    if (bounded.evicts()) {
      check("linkWeight").withMessage("weightedSize != link weights")
          .that(totalWeightedSize).isEqualTo(bounded.weightedSize());
      check("nonNegativeWeight").that(totalWeightedSize).isAtLeast(0);
    }
  }

  private boolean doLinksMatch(BoundedLocalCache<Object, Object> bounded,
      Collection<LinkedDeque<Node<Object, Object>>> deques) {
    bounded.evictionLock.lock();
    try {
      Set<Node<Object, Object>> seen = Sets.newIdentityHashSet();
      for (var deque : deques) {
        scanLinks(bounded, deque, seen);
      }
      return (bounded.size() == seen.size());
    } finally {
      bounded.evictionLock.unlock();
    }
  }

  @CanIgnoreReturnValue
  private long scanLinks(BoundedLocalCache<Object, Object> bounded,
      LinkedDeque<Node<Object, Object>> deque, Set<Node<Object, Object>> seen) {
    long weightedSize = 0;
    Node<?, ?> prev = null;
    for (var node : deque) {
      check("scanLinks").withMessage("Loop detected: %s, saw %s in %s", node, seen, bounded)
          .that(seen.add(node)).isTrue();
      check("getPolicyWeight()").that(node.getPolicyWeight()).isEqualTo(node.getWeight());
      check("getPrevious(node)").that(deque.getPrevious(node)).isEqualTo(prev);
      weightedSize += node.getWeight();
      prev = node;
    }
    return weightedSize;
  }

  private void checkNode(BoundedLocalCache<Object, Object> bounded, Node<Object, Object> node) {
    var key = node.getKey();
    var value = node.getValue();
    checkKey(bounded, node, key, value);
    checkValue(bounded, node, key, value);
    checkWeight(bounded, node, key, value);
    checkRefreshAfterWrite(bounded, node);
  }

  private void checkKey(BoundedLocalCache<Object, Object> bounded,
      Node<Object, Object> node, Object key, Object value) {
    if (bounded.collectKeys()) {
      if ((key != null) && (value != null)) {
        check("bounded").that(bounded).containsKey(key);
      }
      var clazz = node instanceof Interned ? WeakKeyEqualsReference.class : WeakKeyReference.class;
      check("keyReference").that(node.getKeyReference()).isInstanceOf(clazz);
    } else {
      check("key").that(key).isNotNull();
    }
    check("data").that(bounded.data).containsEntry(node.getKeyReference(), node);
  }

  private void checkValue(BoundedLocalCache<Object, Object> bounded,
      Node<Object, Object> node, Object key, Object value) {
    if (!bounded.collectValues()) {
      check("value").that(value).isNotNull();
      if ((key != null) && !bounded.hasExpired(node, bounded.expirationTicker().read())) {
        check("containsValue(value) for key %s", key)
            .about(map()).that(bounded).containsValue(value);
      }
    }
    checkIfAsyncValue(value);
  }

  private void checkIfAsyncValue(Object value) {
    if (value instanceof CompletableFuture<?>) {
      var future = (CompletableFuture<?>) value;
      if (!future.isDone() || future.isCompletedExceptionally()) {
        failWithActual("future was not completed successfully", actual);
      }
      check("future").that(future.join()).isNotNull();
    }
  }

  private void checkWeight(BoundedLocalCache<Object, Object> bounded,
      Node<Object, Object> node, Object key, Object value) {
    check("node.getWeight").that(node.getWeight()).isAtLeast(0);

    var weigher = bounded.weigher;
    boolean canCheckWeight = (weigher == Weighers.random());
    if (weigher instanceof AsyncWeigher) {
      @SuppressWarnings("rawtypes")
      var asyncWeigher = (AsyncWeigher) weigher;
      canCheckWeight = (asyncWeigher.delegate == Weighers.random());
    }
    if (canCheckWeight) {
      check("node.getWeight()").that(node.getWeight()).isEqualTo(weigher.weigh(key, value));
    }
  }

  private void checkRefreshAfterWrite(
      BoundedLocalCache<Object, Object> bounded, Node<Object, Object> node) {
    if (bounded.refreshAfterWrite()) {
      check("node.getWriteTime()").that(node.getWriteTime()).isNotEqualTo(Long.MAX_VALUE);
    }
  }

  /* --------------- Unbounded --------------- */

  private void checkUnbounded(UnboundedLocalCache<?, ?> unbounded) {
    if (unbounded.isEmpty()) {
      check("unbounded").about(map()).that(unbounded).isExhaustivelyEmpty();
    }
    unbounded.data.forEach((key, value) -> {
      check("key").that(key).isNotNull();
      check("value").that(value).isNotNull();
      checkIfAsyncValue(value);
    });
    checkStats(unbounded);
  }
}
