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

import static com.github.benmanes.caffeine.cache.BLCHeader.DrainStatusRef.IDLE;
import static com.github.benmanes.caffeine.cache.LinkedDequeSubject.deque;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.MapSubject.map;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Objects.requireNonNull;

import java.lang.ref.Reference;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

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
import com.github.benmanes.caffeine.cache.Weighers.SkippedWeigher;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Var;

/**
 * Propositions for {@link LocalCache}-based subjects.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("GuardedBy")
public final class LocalCacheSubject extends Subject {
  private static final int NO_QUEUE_TYPE = -1;

  private final Object actual;

  private LocalCacheSubject(FailureMetadata metadata, @Nullable Object subject) {
    super(metadata, subject);
    this.actual = requireNonNull(subject);
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
      var bounded = (BoundedLocalAsyncCache<?, ?>) actual;
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
        var bounded = (BoundedLocalAsyncLoadingCache<?, ?>) async;
        checkBounded(bounded.cache);
      } else if (async instanceof UnboundedLocalAsyncLoadingCache<?, ?>) {
        var unbounded = (UnboundedLocalAsyncLoadingCache<?, ?>) async;
        checkUnbounded(unbounded.cache);
      }
    } else if (actual instanceof LocalAsyncCache.AsMapView<?, ?>) {
      var asMap = (LocalAsyncCache.AsMapView<?, ?>) actual;
      if (asMap.delegate instanceof BoundedLocalCache<?, ?>) {
        var bounded = (BoundedLocalCache<?, ?>) asMap.delegate;
        checkBounded(bounded);
      } else if (asMap.delegate instanceof UnboundedLocalCache<?, ?>) {
        var unbounded = (UnboundedLocalCache<?, ?>) asMap.delegate;
        checkUnbounded(unbounded);
      }
    }
  }

  private static void checkStats(LocalCache<?, ?> cache) {
    if (cache.isRecordingStats()) {
      assertThat(cache.statsTicker()).isSameInstanceAs(Ticker.systemTicker());
      assertThat(cache.statsCounter()).isNotSameInstanceAs(StatsCounter.disabledStatsCounter());
    } else {
      assertThat(cache.statsTicker()).isSameInstanceAs(Ticker.disabledTicker());
      assertThat(cache.statsCounter()).isSameInstanceAs(StatsCounter.disabledStatsCounter());
    }
  }

  /* --------------- Bounded --------------- */

  private <K, V> void checkBounded(BoundedLocalCache<K, V> bounded) {
    drain(bounded);
    checkReadBuffer(bounded);

    checkCache(bounded);
    checkStats(bounded);
    checkTimerWheel(bounded);
    checkEvictionDeque(bounded);
  }

  private <K, V> void drain(BoundedLocalCache<K, V> bounded) {
    @Var long adjustment = 0;
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

    check("writeBuffer").withMessage("writeBuffer not empty after drain")
        .that(bounded.writeBuffer.isEmpty()).isTrue();
    if (bounded.drainStatusOpaque() != IDLE) {
      await().pollInSameThread().until(() -> bounded.drainStatusOpaque() == IDLE);
    }
    check("drainStatus").that(bounded.drainStatusOpaque()).isEqualTo(IDLE);
  }

  private static <K, V> void checkReadBuffer(BoundedLocalCache<K, V> bounded) {
    if (!tryDrainBuffers(bounded)) {
      await().pollInSameThread().until(() -> tryDrainBuffers(bounded));
    }
  }

  private static <K, V> boolean tryDrainBuffers(BoundedLocalCache<K, V> bounded) {
    bounded.cleanUp();
    var buffer = bounded.readBuffer;
    return (buffer.size() == 0) && (buffer.reads() == buffer.writes());
  }

  private <K, V> void checkCache(BoundedLocalCache<K, V> bounded) {
    @Var long remainingNanos = TimeUnit.SECONDS.toNanos(5);
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
        checkFrequencySketch(bounded);
        checkHillClimber(bounded);
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

  private <K, V> void checkFrequencySketch(BoundedLocalCache<K, V> bounded) {
    var sketch = bounded.frequencySketch();
    if (sketch.isNotInitialized()) {
      return;
    }
    check("sketch.size").that(sketch.size).isAtLeast(0);
    check("sketch.table").that(sketch.table).isNotNull();
    check("sketch.sampleSize").that(sketch.sampleSize).isAtLeast(10);
    check("sketch.size").that(sketch.size).isLessThan(sketch.sampleSize);
    check("sketch.blockMask").that(sketch.blockMask).isEqualTo((sketch.table.length >>> 3) - 1);
  }

  @SuppressWarnings("MathClampDouble")
  private <K, V> void checkHillClimber(BoundedLocalCache<K, V> bounded) {
    check("hitsInSample").that(bounded.hitsInSample()).isAtLeast(0);
    check("missesInSample").that(bounded.missesInSample()).isAtLeast(0);
    if (!bounded.frequencySketch().isNotInitialized()) {
      long requestCount = bounded.hitsInSample() + bounded.missesInSample();
      @Var long effectiveSampleSize = bounded.frequencySketch().sampleSize;
      long maximum = bounded.maximum();
      if ((maximum > 0) && (maximum <= BoundedLocalCache.SMALL_CACHE_THRESHOLD)) {
        // Mirror the adaptive sample-period gate in BoundedLocalCache#determineAdjustment.
        double initialStep = BoundedLocalCache.HILL_CLIMBER_STEP_PERCENT * maximum;
        double magnitude = Math.max(
            initialStep / BoundedLocalCache.SMALL_CACHE_SAMPLE_RATIO_CAP,
            Math.abs(bounded.stepSize()));
        double ratio = Math.max(1.0, Math.min(
            BoundedLocalCache.SMALL_CACHE_SAMPLE_RATIO_CAP, initialStep / magnitude));
        effectiveSampleSize = (long) (effectiveSampleSize * ratio);
      }
      check("hitsInSample + missesInSample")
          .that(requestCount).isLessThan(effectiveSampleSize);
    }
  }

  private <K, V> void checkTimerWheel(BoundedLocalCache<K, V> bounded) {
    if (!bounded.expiresVariable()) {
      return;
    } else if (!doesTimerWheelMatch(bounded)) {
      await().pollInSameThread().until(() -> doesTimerWheelMatch(bounded));
    }

    var seen = Collections.newSetFromMap(new IdentityHashMap<>(bounded.size()));
    var wheel = bounded.timerWheel().wheel;
    for (Node<K, V>[] bucket : wheel) {
      for (Node<K, V> sentinel : bucket) {
        check("first").that(sentinel).isInstanceOf(Sentinel.class);
        check("previousInVariableOrder")
            .that(sentinel.getPreviousInVariableOrder().getNextInVariableOrder())
            .isSameInstanceAs(sentinel);
        check("nextInVariableOrder")
            .that(sentinel.getNextInVariableOrder().getPreviousInVariableOrder())
            .isSameInstanceAs(sentinel);

        // Variable expiry updates the timestamp via CAS and enqueues the reschedule through the
        // lossy read buffer, so a dropped reschedule leaves the node in its previous bucket while
        // variableTime moves on. The user's Expiry can return arbitrarily larger or smaller
        // durations, so the bucket can drift to either a higher or lower level than the current
        // variableTime would imply. When a read shortens the duration but its reschedule is
        // dropped, the node lingers in a higher bucket while time advances past its variableTime,
        // so it can appear arbitrarily stale until the next read evicts it or the wheel cycles to
        // its bucket. The wheel self-corrects on advance(), so neither the bucket placement nor how
        // stale a node appears relative to the current time are valid structural invariants here;
        // only the linkage and the node count are checked.
        @Var var node = sentinel.getNextInVariableOrder();
        while (node != sentinel) {
          var next = node.getNextInVariableOrder();
          var prev = node.getPreviousInVariableOrder();
          check("loopDetected").that(seen.add(node)).isTrue();
          check("wrongPrev").that(prev.getNextInVariableOrder()).isSameInstanceAs(node);
          check("wrongNext").that(next.getPreviousInVariableOrder()).isSameInstanceAs(node);
          node = node.getNextInVariableOrder();
        }
      }
    }
    check("cache.size() == timerWheel.size()").that(bounded).hasSize(seen.size());
  }

  private static <K, V> boolean doesTimerWheelMatch(BoundedLocalCache<K, V> bounded) {
    bounded.evictionLock.lock();
    try {
      var seen = Collections.newSetFromMap(new IdentityHashMap<>(bounded.size()));
      for (int i = 0; i < bounded.timerWheel().wheel.length; i++) {
        for (int j = 0; j < bounded.timerWheel().wheel[i].length; j++) {
          var sentinel = bounded.timerWheel().wheel[i][j];
          @Var var node = sentinel.getNextInVariableOrder();
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

  private <K, V> void checkEvictionDeque(BoundedLocalCache<K, V> bounded) {
    if (bounded.evicts()) {
      long mainProbation = bounded.weightedSize()
          - bounded.windowWeightedSize() - bounded.mainProtectedWeightedSize();
      var checks = List.of(
          new DequeCheck<>("window", bounded.windowWeightedSize(),
              bounded.accessOrderWindowDeque(), Node.WINDOW),
          new DequeCheck<>("probation", mainProbation,
              bounded.accessOrderProbationDeque(), Node.PROBATION),
          new DequeCheck<>("protected", bounded.mainProtectedWeightedSize(),
              bounded.accessOrderProtectedDeque(), Node.PROTECTED));
      checkLinks(bounded, checks);
      check("accessOrderWindowDeque()").about(deque())
          .that(bounded.accessOrderWindowDeque()).isValid();
      check("accessOrderProbationDeque()").about(deque())
          .that(bounded.accessOrderProbationDeque()).isValid();
    } else if (bounded.expiresAfterAccess()) {
      checkLinks(bounded, List.of(new DequeCheck<>("window",
          bounded.estimatedSize(), bounded.accessOrderWindowDeque(), Node.WINDOW)));
      check("accessOrderWindowDeque()").about(deque())
          .that(bounded.accessOrderWindowDeque()).isValid();
    }

    if (bounded.expiresAfterWrite()) {
      long expectedSize = bounded.evicts() ? bounded.weightedSize() : bounded.estimatedSize();
      checkLinks(bounded, List.of(
          new DequeCheck<>("writeOrder", expectedSize, bounded.writeOrderDeque(), NO_QUEUE_TYPE)));
      check("writeOrderDeque()").about(deque())
          .that(bounded.writeOrderDeque()).isValid();
    }
  }

  private <K, V> void checkLinks(BoundedLocalCache<K, V> bounded, List<DequeCheck<K, V>> checks) {
    if (!doLinksMatch(bounded, checks)) {
      await().pollInSameThread().until(() -> doLinksMatch(bounded, checks));
    }

    @Var int totalSize = 0;
    @Var long totalWeightedSize = 0;
    Set<Node<K, V>> seen = Collections.newSetFromMap(
        new IdentityHashMap<>(bounded.size()));
    for (var dequeCheck : checks) {
      long weightedSize = scanLinks(bounded, dequeCheck, seen);
      check("%s: %s in %s", dequeCheck.name, dequeCheck.deque, bounded.data)
          .that(weightedSize).isEqualTo(dequeCheck.expectedWeight);
      totalSize += dequeCheck.deque.size();
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

  private <K, V> boolean doLinksMatch(BoundedLocalCache<K, V> bounded,
      Collection<DequeCheck<K, V>> checks) {
    bounded.evictionLock.lock();
    try {
      Set<Node<K, V>> seen = Collections.newSetFromMap(
          new IdentityHashMap<>(bounded.size()));
      for (var dequeCheck : checks) {
        scanLinks(bounded, dequeCheck, seen);
      }
      return (bounded.size() == seen.size());
    } finally {
      bounded.evictionLock.unlock();
    }
  }

  @CanIgnoreReturnValue
  private <K, V> long scanLinks(BoundedLocalCache<K, V> bounded,
      DequeCheck<K, V> dequeCheck, Set<Node<K, V>> seen) {
    @Var long weightedSize = 0;
    @Var Node<?, ?> prev = null;
    for (var node : dequeCheck.deque) {
      check("scanLinks").withMessage("Loop detected: %s, saw %s in %s", node, seen, bounded)
          .that(seen.add(node)).isTrue();
      check("getPolicyWeight()").that(node.getPolicyWeight()).isEqualTo(node.getWeight());
      check("getPrevious(node)").that(dequeCheck.deque.getPrevious(node)).isEqualTo(prev);
      check("dataContainment for %s", node)
          .that(bounded.data.get(node.getKeyReference())).isSameInstanceAs(node);
      if (dequeCheck.expectedQueueType != NO_QUEUE_TYPE) {
        check("queueType for %s", node)
            .that(node.getQueueType()).isEqualTo(dequeCheck.expectedQueueType);
      }
      weightedSize += node.getWeight();
      prev = node;
    }
    return weightedSize;
  }

  private <K, V> void checkNode(BoundedLocalCache<K, V> bounded, Node<K, V> node) {
    var key = node.getKey();
    var value = node.getValue();
    checkNodeState(node);
    checkKey(bounded, node, key, value);
    checkValue(bounded, node, key, value);
    checkWeight(bounded, node, key, value);
    checkRefreshAfterWrite(bounded, node);
  }

  private <K, V> void checkNodeState(Node<K, V> node) {
    check("isAlive").withMessage("node in data must be alive: %s", node)
        .that(node.isAlive()).isTrue();
    check("isRetired").withMessage("node in data must not be retired: %s", node)
        .that(node.isRetired()).isFalse();
    check("isDead").withMessage("node in data must not be dead: %s", node)
        .that(node.isDead()).isFalse();
  }

  private <K, V> void checkKey(BoundedLocalCache<K, V> bounded,
      Node<K, V> node, @Nullable K key, @Nullable V value) {
    if (bounded.collectKeys()) {
      if ((key != null) && (value != null)) {
        check("bounded").that(bounded.data).containsKey(node.getKeyReference());
        if (!bounded.hasExpired(node, bounded.expirationTicker().read(), value)) {
          check("bounded").that(bounded).containsKey(key);
        }
      }
      var clazz = node instanceof Interned ? WeakKeyEqualsReference.class : WeakKeyReference.class;
      check("keyReference").that(node.getKeyReference()).isInstanceOf(clazz);
      if (key == null) {
        check("keyReferenceOrNull").that(node.getKeyReferenceOrNull()).isNull();
      } else {
        check("keyReferenceOrNull").that(node.getKeyReferenceOrNull())
            .isSameInstanceAs(node.getKeyReference());
        var reference = (Reference<?>) node.getKeyReference();
        check("weakKeyReference.get()").that(reference.get()).isSameInstanceAs(key);
      }
    } else {
      check("key").that(key).isNotNull();
      check("keyReferenceOrNull").that(node.getKeyReferenceOrNull()).isSameInstanceAs(key);
    }
    check("data").that(bounded.data).containsEntry(node.getKeyReference(), node);
  }

  private <K, V> void checkValue(BoundedLocalCache<K, V> bounded,
      Node<K, V> node, @Nullable K key, @Nullable V value) {
    if (!bounded.collectValues()) {
      check("value").that(value).isNotNull();
      requireNonNull(value);
      if ((key != null) && !bounded.hasExpired(node, bounded.expirationTicker().read(), value)) {
        check("containsValue(value) for key %s", key)
            .about(map()).that(bounded).containsValue(value);
      }
    }
    checkIfAsyncValue(bounded.isAsync(), value);
  }

  private void checkIfAsyncValue(boolean isAsync, @Nullable Object value) {
    if (isAsync && (value != null)) {
      check("asyncValueType").that(value).isInstanceOf(CompletableFuture.class);
    }
    if (value instanceof CompletableFuture<?>) {
      var future = (CompletableFuture<?>) value;
      if (!future.isDone() || future.isCompletedExceptionally()) {
        failWithActual("future was not completed successfully", actual);
      }
      check("future").that(future.join()).isNotNull();
    }
  }

  private <K, V> void checkWeight(BoundedLocalCache<K, V> bounded,
      Node<K, V> node, @Nullable K key, @Nullable V value) {
    check("node.getWeight").that(node.getWeight()).isAtLeast(0);
    if ((key == null) || (value == null)) {
      return;
    }

    @Var Weigher<?, ?> weigher = bounded.weigher;
    if (weigher instanceof AsyncWeigher) {
      weigher = ((AsyncWeigher<?, ?>) weigher).delegate;
    }
    if (weigher instanceof BoundedWeigher) {
      weigher = ((BoundedWeigher<?, ?>) weigher).delegate;
    }
    if (!(weigher instanceof SkippedWeigher)) {
      int weight = bounded.weigher.weigh(key, value);
      check("node.getWeight()").that(node.getWeight()).isEqualTo(weight);
    }
  }

  private <K, V> void checkRefreshAfterWrite(BoundedLocalCache<K, V> bounded, Node<K, V> node) {
    if (bounded.refreshAfterWrite()) {
      check("node.getWriteTime()").that(node.getWriteTime()).isNotEqualTo(Long.MAX_VALUE);
    }
  }

  /* --------------- Unbounded --------------- */

  private void checkUnbounded(UnboundedLocalCache<?, ?> unbounded) {
    check("size").withMessage("cache.size() equal to data.size()")
        .that(unbounded.size()).isEqualTo(unbounded.data.size());
    if (unbounded.isEmpty()) {
      check("unbounded").about(map()).that(unbounded).isExhaustivelyEmpty();
    }
    unbounded.data.forEach((key, value) -> {
      check("key").that(key).isNotNull();
      check("value").that(value).isNotNull();
      checkIfAsyncValue(unbounded.isAsync(), value);
    });
    checkStats(unbounded);
  }

  private static final class DequeCheck<K, V> {
    final LinkedDeque<Node<K, V>> deque;
    final int expectedQueueType;
    final long expectedWeight;
    final String name;

    DequeCheck(String name, long expectedWeight,
        LinkedDeque<Node<K, V>> deque, int expectedQueueType) {
      this.name = requireNonNull(name);
      this.deque = requireNonNull(deque);
      this.expectedWeight = expectedWeight;
      this.expectedQueueType = expectedQueueType;
    }
  }
}
