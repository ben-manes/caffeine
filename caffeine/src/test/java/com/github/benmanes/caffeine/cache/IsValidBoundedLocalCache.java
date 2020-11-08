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

import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.Async.AsyncWeigher;
import com.github.benmanes.caffeine.cache.References.WeakKeyReference;
import com.github.benmanes.caffeine.cache.TimerWheel.Sentinel;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.testing.DescriptionBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link BoundedLocalCache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("GuardedBy")
public final class IsValidBoundedLocalCache<K, V>
    extends TypeSafeDiagnosingMatcher<BoundedLocalCache<K, V>> {
  DescriptionBuilder desc;

  @Override
  public void describeTo(Description description) {
    description.appendText("valid bounded cache");
    if (desc.getDescription() != description) {
      description.appendText(desc.getDescription().toString());
    }
  }

  @Override
  protected boolean matchesSafely(BoundedLocalCache<K, V> cache, Description description) {
    desc = new DescriptionBuilder(description);

    drain(cache);
    checkReadBuffer(cache);

    checkCache(cache);
    checkTimerWheel(cache);
    checkEvictionDeque(cache);

    if (!desc.matches()) {
      throw new AssertionError(desc.getDescription().toString());
    }
    return true;
  }

  private void drain(BoundedLocalCache<K, V> cache) {
    do {
      cache.cleanUp();
    } while (cache.buffersWrites() && cache.writeBuffer().size() > 0);
  }

  private void checkReadBuffer(BoundedLocalCache<K, V> cache) {
    if (!tryDrainBuffers(cache)) {
      await().pollInSameThread().until(() -> tryDrainBuffers(cache));
    }
  }

  private Boolean tryDrainBuffers(BoundedLocalCache<K, V> cache) {
    cache.cleanUp();
    Buffer<?> buffer = cache.readBuffer;
    return (buffer.size() == 0) && buffer.reads() == buffer.writes();
  }

  private void checkCache(BoundedLocalCache<K, V> cache) {
    for (;;) {
      long remainingNanos = TimeUnit.SECONDS.toNanos(5);
      long end = System.nanoTime() + remainingNanos;
      try {
        if (cache.evictionLock.tryLock(remainingNanos, TimeUnit.NANOSECONDS)) {
          cache.evictionLock.unlock();
        } else {
          desc.expected("Maintenance lock cannot be acquired");
        }
        break;
      } catch (InterruptedException ignored) {
        remainingNanos = end - System.nanoTime();
      }
    }

    desc.expectThat("Inconsistent size", cache.data.size(), is(cache.size()));
    if (cache.evicts()) {
      cache.evictionLock.lock();
      try {
        long weightedSize = cache.weightedSize();
        desc.expectThat("overflow", cache.maximum(), is(greaterThanOrEqualTo(weightedSize)));
      } finally {
        cache.evictionLock.unlock();
      }
    }

    if (cache.isEmpty()) {
      desc.expectThat("empty map", cache, emptyMap());
    }

    for (Node<K, V> node : cache.data.values()) {
      checkNode(cache, node, desc);
    }
  }

  private void checkTimerWheel(BoundedLocalCache<K, V> cache) {
    if (!cache.expiresVariable()) {
      return;
    } else if (!doesTimerWheelMatch(cache)) {
      await().pollInSameThread().until(() -> doesTimerWheelMatch(cache));
    }

    Set<Node<K, V>> seen = Sets.newIdentityHashSet();
    for (int i = 0; i < cache.timerWheel().wheel.length; i++) {
      for (int j = 0; j < cache.timerWheel().wheel[i].length; j++) {
        Node<K, V> sentinel = cache.timerWheel().wheel[i][j];
        desc.expectThat("Wrong sentinel prev",
            sentinel.getPreviousInVariableOrder().getNextInVariableOrder(), sameInstance(sentinel));
        desc.expectThat("Wrong sentinel next",
            sentinel.getNextInVariableOrder().getPreviousInVariableOrder(), sameInstance(sentinel));
        desc.expectThat("Sentinel must be first element", sentinel, instanceOf(Sentinel.class));

        for (Node<K, V> node = sentinel.getNextInVariableOrder();
            node != sentinel; node = node.getNextInVariableOrder()) {
          Node<K, V> next = node.getNextInVariableOrder();
          Node<K, V> prev = node.getPreviousInVariableOrder();
          long duration = node.getVariableTime() - cache.timerWheel().nanos;
          desc.expectThat("Expired", duration, greaterThan(0L));
          desc.expectThat("Loop detected", seen.add(node), is(true));
          desc.expectThat("Wrong prev", prev.getNextInVariableOrder(), is(sameInstance(node)));
          desc.expectThat("Wrong next", next.getPreviousInVariableOrder(), is(sameInstance(node)));
        }
      }
    }
    desc.expectThat("Timers != Entries", seen, hasSize(cache.size()));
  }

  private boolean doesTimerWheelMatch(BoundedLocalCache<K, V> cache) {
    cache.evictionLock.lock();
    try {
      Set<Node<K, V>> seen = Sets.newIdentityHashSet();
      for (int i = 0; i < cache.timerWheel().wheel.length; i++) {
        for (int j = 0; j < cache.timerWheel().wheel[i].length; j++) {
          Node<K, V> sentinel = cache.timerWheel().wheel[i][j];

          for (Node<K, V> node = sentinel.getNextInVariableOrder();
              node != sentinel; node = node.getNextInVariableOrder()) {
            if (!seen.add(node)) {
              return false;
            }
          }
        }
      }
      return cache.size() == seen.size();
    } finally {
      cache.evictionLock.unlock();
    }
  }

  private void checkEvictionDeque(BoundedLocalCache<K, V> cache) {
    if (cache.evicts()) {
      ImmutableList<LinkedDeque<Node<K, V>>> deques = ImmutableList.of(
          cache.accessOrderWindowDeque(),
          cache.accessOrderProbationDeque(),
          cache.accessOrderProtectedDeque());
      checkLinks(cache, deques, desc);
      checkDeque(cache.accessOrderWindowDeque(), desc);
      checkDeque(cache.accessOrderProbationDeque(), desc);
    } else if (cache.expiresAfterAccess()) {
      checkLinks(cache, ImmutableList.of(cache.accessOrderWindowDeque()), desc);
      checkDeque(cache.accessOrderWindowDeque(), desc);
    }

    if (cache.expiresAfterWrite()) {
      checkLinks(cache, ImmutableList.of(cache.writeOrderDeque()), desc);
      checkDeque(cache.writeOrderDeque(), desc);
    }
  }

  private void checkDeque(LinkedDeque<Node<K, V>> deque, DescriptionBuilder desc) {
    IsValidLinkedDeque.<Node<K, V>>validLinkedDeque().matchesSafely(deque, desc.getDescription());
  }

  private void checkLinks(BoundedLocalCache<K, V> cache,
      ImmutableList<LinkedDeque<Node<K, V>>> deques, DescriptionBuilder desc) {
    if (!doLinksMatch(cache, deques)) {
      await().pollInSameThread().until(() -> doLinksMatch(cache, deques));
    }

    int size = 0;
    long weightedSize = 0;
    Set<Node<K, V>> seen = Sets.newIdentityHashSet();
    for (LinkedDeque<Node<K, V>> deque : deques) {
      size += deque.size();
      weightedSize += scanLinks(cache, seen, deque, desc);
    }
    if (cache.size() != size) {
      desc.expectThat(() -> "deque size " + deques, size, is(cache.size()));
    }

    Supplier<String> errorMsg = () -> String.format(
        "Size != list length; pending=%s, additional: %s", cache.writeBuffer().size(),
        Sets.difference(seen, ImmutableSet.copyOf(cache.data.values())));
    desc.expectThat(errorMsg, cache.size(), is(seen.size()));

    if (cache.evicts()) {
      long weighted = weightedSize;
      long expectedWeightedSize = Math.max(0, cache.weightedSize());
      Supplier<String> error = () -> String.format(
          "WeightedSize != link weights [%d vs %d] {%d vs %d}",
          expectedWeightedSize, weighted, seen.size(), cache.size());
      desc.expectThat("non-negative weight", weightedSize, is(greaterThanOrEqualTo(0L)));
      desc.expectThat(error, expectedWeightedSize, is(weightedSize));
    }
  }

  private boolean doLinksMatch(BoundedLocalCache<K, V> cache,
      ImmutableList<LinkedDeque<Node<K, V>>> deques) {
    cache.evictionLock.lock();
    try {
      Set<Node<K, V>> seen = Sets.newIdentityHashSet();
      for (LinkedDeque<Node<K, V>> deque : deques) {
        scanLinks(cache, seen, deque, desc);
      }
      return cache.size() == seen.size();
    } finally {
      cache.evictionLock.unlock();
    }
  }

  private long scanLinks(BoundedLocalCache<K, V> cache, Set<Node<K, V>> seen,
      LinkedDeque<Node<K, V>> deque, DescriptionBuilder desc) {
    long weightedSize = 0;
    Node<?, ?> prev = null;
    for (Node<K, V> node : deque) {
      Supplier<String> errorMsg = () -> String.format(
          "Loop detected: %s, saw %s in %s", node, seen, cache);
      desc.expectThat(errorMsg, seen.add(node), is(true));
      desc.expectThat("wrong previous", deque.getPrevious(node), is(prev));
      desc.expectThat("policyWeight != weight", node.getPolicyWeight(), is(node.getWeight()));
      weightedSize += node.getWeight();
      prev = node;
    }
    return weightedSize;
  }

  private void checkNode(BoundedLocalCache<K, V> cache, Node<K, V> node, DescriptionBuilder desc) {
    Weigher<? super K, ? super V> weigher = cache.weigher;
    V value = node.getValue();
    K key = node.getKey();

    desc.expectThat("weight", node.getWeight(), is(greaterThanOrEqualTo(0)));

    boolean canCheckWeight = weigher == CacheWeigher.RANDOM;
    if (weigher instanceof AsyncWeigher) {
      canCheckWeight = ((AsyncWeigher<?, ?>) weigher).delegate == CacheWeigher.RANDOM;
    }
    if (canCheckWeight) {
      desc.expectThat("weight", node.getWeight(), is(weigher.weigh(key, value)));
    }

    if (cache.collectKeys()) {
      if ((key != null) && (value != null)) {
        desc.expectThat("inconsistent", cache.containsKey(key), is(true));
      }
      desc.expectThat("Invalid reference type",
          node.getKeyReference(), instanceOf(WeakKeyReference.class));
    } else {
      desc.expectThat("not null key", key, is(not(nullValue())));
    }
    desc.expectThat("found wrong node", cache.data.get(node.getKeyReference()), is(node));

    if (!cache.collectValues()) {
      desc.expectThat("not null value", value, is(not(nullValue())));
      if ((key != null) && !cache.hasExpired(node, cache.expirationTicker().read())) {
        desc.expectThat(() -> "Could not find key: " + key + ", value: " + value,
            cache.containsValue(value), is(true));
      }
    }

    if (cache.refreshAfterWrite()) {
      desc.expectThat("infinite timestamp", node.getWriteTime(), is(not(Long.MAX_VALUE)));
    }

    if (value instanceof CompletableFuture<?>) {
      CompletableFuture<?> future = (CompletableFuture<?>) value;
      boolean success = future.isDone() && !future.isCompletedExceptionally();
      desc.expectThat("future is done", success, is(true));
      desc.expectThat("not null value", future.getNow(null), is(not(nullValue())));
    }
  }

  public static <K, V> IsValidBoundedLocalCache<K, V> valid() {
    return new IsValidBoundedLocalCache<>();
  }
}
