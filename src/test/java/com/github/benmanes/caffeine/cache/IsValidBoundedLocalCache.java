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

import static com.github.benmanes.caffeine.cache.IsValidLinkedDeque.validLinkedDeque;
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
import com.github.benmanes.caffeine.cache.BoundedLocalCache.WeightedValue;
import com.github.benmanes.caffeine.matchers.DescriptionBuilder;
import com.google.common.collect.Sets;

/**
 * A matcher that evaluates a {@link BoundedLocalCache} to determine if it is in a valid state.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class IsValidBoundedLocalCache<K, V>
    extends TypeSafeDiagnosingMatcher<BoundedLocalCache<? extends K, ? extends V>> {

  @Override
  public void describeTo(Description description) {
    description.appendText("valid");
  }

  @Override
  protected boolean matchesSafely(BoundedLocalCache<? extends K, ? extends V> map,
      Description description) {
    DescriptionBuilder desc = new DescriptionBuilder(description);

    drain(map);
    checkMap(map, desc);
    checkEvictionDeque(map, desc);
    return desc.matches();
  }

  private void drain(BoundedLocalCache<? extends K, ? extends V> map) {
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

  private void checkMap(BoundedLocalCache<? extends K, ? extends V> map, DescriptionBuilder desc) {
    desc.expectThat("Inconsistent size", map.data.size(), is(map.size()));
    desc.expectThat("weightedSize", map.weightedSize(), is(map.weightedSize.get()));
    desc.expectThat("capacity", map.capacity(), is(map.capacity.get()));
    desc.expectThat("overflow", map.capacity.get(),
        is(greaterThanOrEqualTo(map.weightedSize())));
    desc.expectThat(((BoundedLocalCache.Sync) map.evictionLock).isLocked(), is(false));

    if (map.isEmpty()) {
      desc.expectThat(map, emptyMap());
    }
  }

  private void checkEvictionDeque(BoundedLocalCache<? extends K, ? extends V> map,
      DescriptionBuilder desc) {
    LinkedDeque<?> deque = map.evictionDeque;

    checkLinks(map, desc);
    desc.expectThat(deque, hasSize(map.size()));
    validLinkedDeque().matchesSafely(map.evictionDeque, desc.getDescription());
  }

  @SuppressWarnings("rawtypes")
  private void checkLinks(BoundedLocalCache<? extends K, ? extends V> map,
      DescriptionBuilder desc) {
    long weightedSize = 0;
    Set<Node> seen = Sets.newIdentityHashSet();
    for (Node<? extends K, ? extends V> node : map.evictionDeque) {
      Supplier<String> errorMsg = () -> String.format(
          "Loop detected: %s, saw %s in %s", node, seen, map);
      desc.expectThat(errorMsg, seen.add(node), is(true));
      weightedSize += ((WeightedValue) node.get()).weight;
      checkNode(map, node, desc);
    }

    desc.expectThat("Size != list length", map.size(), is(seen.size()));
    desc.expectThat("WeightedSize != link weights"
        + " [" + map.weightedSize() + " vs. " + weightedSize + "]"
        + " {size: " + map.size() + " vs. " + seen.size() + "}",
        map.weightedSize(), is(weightedSize));
  }

  private void checkNode(BoundedLocalCache<? extends K, ? extends V> map,
      Node<? extends K, ? extends V> node, DescriptionBuilder builder) {
    @SuppressWarnings("unchecked")
    Weigher<Object, Object> weigher = (Weigher<Object, Object>) map.weigher;

    builder.expectThat(node.key, is(not(nullValue())));
    builder.expectThat(node.get(), is(not(nullValue())));
    builder.expectThat(node.getValue(), is(not(nullValue())));
    builder.expectThat("weight", node.get().weight, is(weigher.weigh(node.key, node.getValue())));

    builder.expectThat("inconsistent", map.containsKey(node.key), is(true));
    builder.expectThat(() -> "Could not find value: " + node.getValue(),
        map.containsValue(node.getValue()), is(true));
    builder.expectThat("found wrong node", map.data.get(node.key), is(node));
  }

  @Factory
  public static <K, V> IsValidBoundedLocalCache<K, V> valid() {
    return new IsValidBoundedLocalCache<K, V>();
  }
}
