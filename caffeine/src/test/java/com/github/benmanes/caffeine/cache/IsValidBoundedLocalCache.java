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

import static com.github.benmanes.caffeine.matchers.IsEmptyMap.emptyMap;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.matchers.DescriptionBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link BoundedLocalCache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
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
    checkCache(cache, desc);
    checkEvictionDeque(cache, desc);
    return desc.matches();
  }

  private void drain(BoundedLocalCache<K, V> cache) {
    while (cache.buffersWrites() && !cache.writeQueue().isEmpty()) {
      cache.cleanUp();
    }

    if (!cache.evicts() && !cache.expiresAfterAccess()) {
      return;
    }
    cache.evictionLock().lock();
    for (int i = 0; i < cache.readBuffers().length; i++) {
      for (;;) {
        cache.drainBuffers();

        boolean fullyDrained = cache.buffersWrites() && cache.writeQueue().isEmpty();
        for (int j = 0; j < cache.readBuffers().length; j++) {
          fullyDrained &= (cache.readBuffers()[i][j].get() == null);
        }
        if (fullyDrained) {
          break;
        }
        cache.readBufferReadCount()[i]++;
      }
    }
    cache.evictionLock().unlock();
  }

  private void checkCache(BoundedLocalCache<K, V> cache, DescriptionBuilder desc) {
    desc.expectThat("Inconsistent size", cache.data.size(), is(cache.size()));
    if (cache.evicts()) {
      desc.expectThat("overflow", cache.maximum(),
          is(greaterThanOrEqualTo(cache.adjustedWeightedSize())));
      desc.expectThat("unlocked", cache.evictionLock().isLocked(), is(false));
    }

    if (cache.isEmpty()) {
      desc.expectThat("empty map", cache, emptyMap());
    }

    for (Node<K, V> node : cache.data.values()) {
      checkNode(cache, node, desc);
    }
  }

  private void checkEvictionDeque(BoundedLocalCache<K, V> cache, DescriptionBuilder desc) {
    if (cache.evicts() || cache.expiresAfterAccess()) {
      checkLinks(cache, cache.accessOrderDeque(), desc);
      checkDeque(cache.accessOrderDeque(), cache.size(), desc);
    }
    if (cache.expiresAfterWrite()) {
      checkLinks(cache, cache.writeOrderDeque(), desc);
      checkDeque(cache.writeOrderDeque(), cache.size(), desc);
    }
  }

  private void checkDeque(LinkedDeque<Node<K, V>> deque, int size, DescriptionBuilder desc) {
    desc.expectThat(() -> "deque size " + deque, deque, hasSize(size));
    IsValidLinkedDeque.<Node<K, V>>validLinkedDeque().matchesSafely(deque, desc.getDescription());
  }

  private void checkLinks(BoundedLocalCache<K, V> cache,
      LinkedDeque<Node<K, V>> deque, DescriptionBuilder desc) {
    Set<Node<K, V>> seen = Sets.newIdentityHashSet();
    long weightedSize = scanLinks(cache, seen, deque, desc);

    if (seen.size() != cache.size()) {
      // Retry in case race with an async update requiring a clean up
      drain(cache);
      seen.clear();
      weightedSize = scanLinks(cache, seen, deque, desc);

      Supplier<String> errorMsg = () -> String.format(
          "Size != list length; pending=%s, additional: %s", cache.writeQueue().size(),
          Sets.difference(seen, ImmutableSet.copyOf(cache.data.values())));
      desc.expectThat(errorMsg, cache.size(), is(seen.size()));
    }

    final long weighted = weightedSize;
    if (cache.evicts()) {
      Supplier<String> error = () -> String.format(
          "WeightedSize != link weights [%d vs %d] {%d vs %d}",
          cache.adjustedWeightedSize(), weighted, seen.size(), cache.size());
      desc.expectThat("non-negative weight", weightedSize, is(greaterThanOrEqualTo(0L)));
      desc.expectThat(error, cache.adjustedWeightedSize(), is(weightedSize));
    }
  }

  private long scanLinks(BoundedLocalCache<K, V> cache, Set<Node<K, V>> seen,
      LinkedDeque<Node<K, V>> deque, DescriptionBuilder desc) {
    long weightedSize = 0;
    Node<?, ?> prev = null;
    for (Node<K, V> node : deque) {
      Supplier<String> errorMsg = () -> String.format(
          "Loop detected: %s, saw %s in %s", node, seen, cache);
      desc.expectThat("wrong previous", deque.getPrevious(node), is(prev));
      desc.expectThat(errorMsg, seen.add(node), is(true));
      weightedSize += node.getWeight();
      prev = node;
    }
    return weightedSize;
  }

  private void checkNode(BoundedLocalCache<K, V> cache, Node<K, V> node, DescriptionBuilder desc) {
    Weigher<? super K, ? super V> weigher = cache.weigher();
    V value = node.getValue();
    K key = node.getKey();

    desc.expectThat("weight", node.getWeight(), is(greaterThanOrEqualTo(0)));
    desc.expectThat("weight", node.getWeight(), is(weigher.weigh(key, value)));

    if (cache.collectKeys()) {
      if ((key != null) && (value != null)) {
        desc.expectThat("inconsistent", cache.containsKey(key), is(true));
      }
    } else {
      desc.expectThat("not null key", key, is(not(nullValue())));
    }
    desc.expectThat("found wrong node", cache.data.get(node.getKeyReference()), is(node));

    if (!cache.collectValues()) {
      desc.expectThat("not null value", value, is(not(nullValue())));
      desc.expectThat(() -> "Could not find value: " + value, cache.containsValue(value), is(true));
    }

    if (value instanceof CompletableFuture<?>) {
      CompletableFuture<?> future = (CompletableFuture<?>) value;
      boolean success = future.isDone() && !future.isCompletedExceptionally();
      desc.expectThat("future is done", success, is(true));
      desc.expectThat("not null value", future.getNow(null), is(not(nullValue())));
    }
  }

  @Factory
  public static <K, V> IsValidBoundedLocalCache<K, V> valid() {
    return new IsValidBoundedLocalCache<K, V>();
  }
}
