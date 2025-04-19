/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package com.github.benmanes.caffeine.eclipse.mutable;

import static org.eclipse.collections.impl.factory.Iterables.iSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.partition.PartitionIterable;
import org.eclipse.collections.impl.block.factory.IntegerPredicates;
import org.eclipse.collections.impl.block.factory.Predicates2;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.ConcurrentMutableHashMap;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;
import org.eclipse.collections.impl.test.Verify;
import org.eclipse.collections.impl.tuple.ImmutableEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * JUnit test for {@link ConcurrentMutableHashMap}.
 *
 * Ported from Eclipse Collections 11.0.
 */
@ParameterizedClass
@MethodSource("caches")
@SuppressWarnings({"all", "unchecked"})
final class ConcurrentMutableHashMapTest extends ConcurrentHashMapTestCase {

  @Test
  void putIfAbsent() {
    ConcurrentMutableMap<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    assertEquals(Integer.valueOf(1), map.putIfAbsent(1, 1));
    assertNull(map.putIfAbsent(3, 3));
  }

  @Test
  void replace() {
    ConcurrentMutableMap<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    assertEquals(Integer.valueOf(1), map.replace(1, 1));
    assertNull(map.replace(3, 3));
  }

  @Test
  void replaceWithOldValue() {
    ConcurrentMutableMap<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    assertTrue(map.replace(1, 1, 1));
    assertFalse(map.replace(2, 3, 3));
  }

  @Test
  void removeWithKeyValue() {
    ConcurrentMutableMap<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    assertTrue(map.remove(1, 1));
    assertFalse(map.remove(2, 3));
  }

  @Override
  @Test
  void removeFromEntrySet() {
    MutableMap<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertTrue(map.entrySet().remove(ImmutableEntry.of("Two", 2)));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);

    assertFalse(map.entrySet().remove(ImmutableEntry.of("Four", 4)));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Override
  @Test
  void removeAllFromEntrySet() {
    MutableMap<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertTrue(map.entrySet().removeAll(
        FastList.newListWith(ImmutableEntry.of("One", 1), ImmutableEntry.of("Three", 3))));
    assertEquals(UnifiedMap.newWithKeysValues("Two", 2), map);

    assertFalse(map.entrySet().removeAll(FastList.newListWith(ImmutableEntry.of("Four", 4))));
    assertEquals(UnifiedMap.newWithKeysValues("Two", 2), map);
  }

  @Override
  @Test
  void keySetEqualsAndHashCode() {
    MutableMap<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Verify.assertEqualsAndHashCode(UnifiedSet.newSetWith("One", "Two", "Three"), map.keySet());
  }

  @Override
  @Test
  void partition_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2, "C", 3, "D", 4);
    PartitionIterable<Integer> partition = map.partition(IntegerPredicates.isEven());
    assertEquals(iSet(2, 4), partition.getSelected().toSet());
    assertEquals(iSet(1, 3), partition.getRejected().toSet());
  }

  @Override
  @Test
  void partitionWith_value() {
    MapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2, "C", 3, "D", 4);
    PartitionIterable<Integer> partition =
        map.partitionWith(Predicates2.in(), map.select(IntegerPredicates.isEven()));
    assertEquals(iSet(2, 4), partition.getSelected().toSet());
    assertEquals(iSet(1, 3), partition.getRejected().toSet());
  }

  @Override
  void equalsAndHashCode() {
    // java.util.concurrent.ConcurrentHashMap doesn't support null keys OR values
    MapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Verify.assertEqualsAndHashCode(Maps.mutable.of(1, "1", 2, "2", 3, "3"), map);
    Verify.assertEqualsAndHashCode(Maps.immutable.of(1, "1", 2, "2", 3, "3"), map);

    assertNotEquals(map, newMapWithKeysValues(1, "1", 2, "2"));
    assertNotEquals(map, newMapWithKeysValues(1, "1", 2, "2", 3, "3", 4, "4"));
    assertNotEquals(map, newMapWithKeysValues(1, "1", 2, "2", 4, "4"));
  }
}
