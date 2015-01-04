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
import java.util.function.Supplier;

import org.hamcrest.Description;
import org.hamcrest.Factory;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import com.github.benmanes.caffeine.cache.BoundedLocalCache.Node;
import com.github.benmanes.caffeine.matchers.DescriptionBuilder;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link BoundedLocalCache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidBoundedLocalCache<K, V>
    extends TypeSafeDiagnosingMatcher<BoundedLocalCache<K, V>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("valid");
  }

  @Override
  protected boolean matchesSafely(BoundedLocalCache<K, V> map,
      Description description) {
    DescriptionBuilder desc = new DescriptionBuilder(description);

    drain(map);
    checkMap(map, desc);
    checkEvictionDeque(map, desc);
    return desc.matches();
  }

  private void drain(BoundedLocalCache<K, V> map) {
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
    desc.expectThat(((BoundedLocalCache.Sync) map.evictionLock).isLocked(), is(false));

    if (map.isEmpty()) {
      desc.expectThat(map, emptyMap());
    }
  }

  private void checkEvictionDeque(BoundedLocalCache<K, V> map,
      DescriptionBuilder desc) {
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
    desc.expectThat(deque, hasSize(size));
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
      weightedSize += node.get().weight;
      checkNode(map, node, desc);
    }

    final long weighted = weightedSize;
    desc.expectThat("Size != list length", map.size(), is(seen.size()));
    Supplier<String> error = () -> String.format(
        "WeightedSize != link weights [%d vs %d] {%d vs %d}",
        map.weightedSize(), weighted, seen.size(), map.size());
    desc.expectThat(error, map.weightedSize(), is(weightedSize));
  }

  private void checkNode(BoundedLocalCache<K, V> map,
      Node<K, V> node, DescriptionBuilder builder) {
    Weigher<? super K, ? super V> weigher = map.weigher;

    K key = node.getKey(map.keyStrategy);
    builder.expectThat(key, is(not(nullValue())));
    builder.expectThat(node.get(), is(not(nullValue())));
    builder.expectThat(node.getValue(map.valueStrategy), is(not(nullValue())));
    builder.expectThat("weight", node.get().weight,
        is(weigher.weigh(key, node.getValue(map.valueStrategy))));

    builder.expectThat("inconsistent", map.containsKey(key), is(true));
    builder.expectThat(() -> "Could not find value: " + node.getValue(map.valueStrategy),
        map.containsValue(node.getValue(map.valueStrategy)), is(true));
    builder.expectThat("found wrong node", map.data.get(key), is(node));
  }

  @Factory
  public static <K, V> IsValidBoundedLocalCache<K, V> valid() {
    return new IsValidBoundedLocalCache<K, V>();
  }
}
