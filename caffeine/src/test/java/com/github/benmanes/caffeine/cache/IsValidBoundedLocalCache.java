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
  protected boolean matchesSafely(BoundedLocalCache<K, V> map, Description description) {
    desc = new DescriptionBuilder(description);

    drain(map);
    checkMap(map, desc);
    checkEvictionDeque(map, desc);
    return desc.matches();
  }

  private void drain(BoundedLocalCache<K, V> map) {
    map.cleanUp();

    if (!map.evicts() && !map.expiresAfterAccess()) {
      return;
    }

    for (int i = 0; i < map.readBuffers.length; i++) {
      for (;;) {
        map.drainBuffers();

        boolean fullyDrained = map.writeBuffer.isEmpty();
        for (int j = 0; j < map.readBuffers.length; j++) {
          fullyDrained &= (map.readBuffers[i][j].get() == null);
        }
        if (fullyDrained) {
          break;
        }
        map.readBufferReadCount[i]++;
      }
    }
  }

  private void checkMap(BoundedLocalCache<K, V> map, DescriptionBuilder desc) {
    desc.expectThat("Inconsistent size", map.data.size(), is(map.size()));
    if (map.evicts()) {
      desc.expectThat("weightedSize", map.weightedSize(), is(map.weightedSize.get()));
      desc.expectThat("capacity", map.capacity(), is(map.maximumWeightedSize.get()));
      desc.expectThat("overflow", map.maximumWeightedSize.get(),
          is(greaterThanOrEqualTo(map.weightedSize())));
    }
    desc.expectThat("unlocked", map.evictionLock.isLocked(), is(false));

    if (map.isEmpty()) {
      desc.expectThat("empty map", map, emptyMap());
    }

    for (Node<K, V> node : map.data.values()) {
      checkNode(map, node, desc);
    }
  }

  private void checkEvictionDeque(BoundedLocalCache<K, V> map, DescriptionBuilder desc) {
    if (map.evicts() || map.expiresAfterAccess()) {
      checkLinks(map, map.accessOrderDeque, desc);
      checkDeque(map.accessOrderDeque, map.size(), desc);
    }
    if (map.expiresAfterWrite()) {
      checkLinks(map, map.writeOrderDeque, desc);
      checkDeque(map.writeOrderDeque, map.size(), desc);
    }
  }

  private void checkDeque(LinkedDeque<Node<K, V>> deque, int size, DescriptionBuilder desc) {
    desc.expectThat("deque size", deque, hasSize(size));
    IsValidLinkedDeque.<Node<K, V>>validLinkedDeque().matchesSafely(deque, desc.getDescription());
  }

  private void checkLinks(BoundedLocalCache<K, V> map,
      LinkedDeque<Node<K, V>> deque, DescriptionBuilder desc) {
    long weightedSize = 0;
    Set<Node<K, V>> seen = Sets.newIdentityHashSet();
    for (Node<K, V> node : deque) {
      Supplier<String> errorMsg = () -> String.format(
          "Loop detected: %s, saw %s in %s", node, seen, map);
      desc.expectThat(errorMsg, seen.add(node), is(true));
      weightedSize += node.getWeight();
    }

    final long weighted = weightedSize;
    desc.expectThat("Size != list length", map.size(), is(seen.size()));
    Supplier<String> error = () -> String.format(
        "WeightedSize != link weights [%d vs %d] {%d vs %d}",
        map.weightedSize(), weighted, seen.size(), map.size());
    desc.expectThat("non-negative weight", weightedSize, is(greaterThanOrEqualTo(0L)));
    desc.expectThat(error, map.weightedSize(), is(weightedSize));
  }

  private void checkNode(BoundedLocalCache<K, V> map, Node<K, V> node, DescriptionBuilder desc) {
    Weigher<? super K, ? super V> weigher = map.weigher;
    V value = node.getValue();
    K key = node.getKey();

    desc.expectThat("weight", node.getWeight(), is(greaterThanOrEqualTo(0)));
    desc.expectThat("weight", node.getWeight(), is(weigher.weigh(key, value)));

    if (map.collectKeys()) {
      if ((key != null) && (value != null)) {
        desc.expectThat("inconsistent", map.containsKey(key), is(true));
      }
    } else {
      desc.expectThat("not null key", key, is(not(nullValue())));
    }
    desc.expectThat("found wrong node", map.data.get(node.getKeyReference()), is(node));

    if (!map.collectValues()) {
      desc.expectThat("not null value", value, is(not(nullValue())));
      desc.expectThat(() -> "Could not find value: " + value, map.containsValue(value), is(true));
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
