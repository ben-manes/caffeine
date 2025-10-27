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

import static org.eclipse.collections.impl.factory.Iterables.iMap;
import static org.eclipse.collections.impl.factory.Iterables.mList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.ImmutableMapIterable;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMapIterable;
import org.eclipse.collections.impl.bag.mutable.HashBag;
import org.eclipse.collections.impl.block.factory.Functions;
import org.eclipse.collections.impl.block.factory.Predicates2;
import org.eclipse.collections.impl.block.function.PassThruFunction0;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.factory.Sets;
import org.eclipse.collections.impl.list.Interval;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.AbstractSynchronizedMapIterable;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;
import org.eclipse.collections.impl.test.Verify;
import org.eclipse.collections.impl.tuple.ImmutableEntry;
import org.eclipse.collections.impl.tuple.Tuples;
import org.eclipse.collections.impl.utility.Iterate;
import org.junit.jupiter.api.Test;

import com.github.benmanes.caffeine.testing.Int;

/**
 * Abstract JUnit TestCase for {@link MutableMapIterable}s.
 *
 * Ported from Eclipse Collections 11.0.
 */
@SuppressWarnings({"all", "deprecation", "unchecked"})
abstract class MutableMapIterableTestCase extends MapIterableTestCase {

  @Test
  void toImmutable() {
    MutableMapIterable<Integer, String> map = newMapWithKeyValue(1, "One");
    ImmutableMapIterable<Integer, String> immutable = map.toImmutable();
    assertEquals(Maps.immutable.with(1, "One"), immutable);
  }

  @Test
  void clear() {
    MutableMapIterable<Integer, Object> map = newMapWithKeysValues(1, "One", 2, "Two", 3, "Three");
    map.clear();
    Verify.assertEmpty(map);

    MutableMapIterable<Object, Object> map2 = newMap();
    map2.clear();
    Verify.assertEmpty(map2);
  }

  @Test
  void removeObject() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    map.remove("Two");
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  void removeFromEntrySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertTrue(map.entrySet().remove(ImmutableEntry.of("Two", 2)));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);

    assertFalse(map.entrySet().remove(ImmutableEntry.of("Four", 4)));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);

    assertFalse(map.entrySet().remove(null));
  }

  @Test
  void removeAllFromEntrySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertTrue(map.entrySet().removeAll(
        FastList.newListWith(ImmutableEntry.of("One", 1), ImmutableEntry.of("Three", 3))));
    assertEquals(UnifiedMap.newWithKeysValues("Two", 2), map);

    assertFalse(map.entrySet().removeAll(FastList.newListWith(ImmutableEntry.of("Four", 4))));
    assertEquals(UnifiedMap.newWithKeysValues("Two", 2), map);

    assertFalse(map.entrySet().remove(null));
  }

  @Test
  void retainAllFromEntrySet() {
    MutableMapIterable<String, Int> map =
        newMapWithKeysValues("One", Int.valueOf(1), "Two", Int.valueOf(2), "Three", Int.valueOf(3));
    assertFalse(map.entrySet()
        .retainAll(FastList.newListWith(ImmutableEntry.of("One", Int.valueOf(1)),
            ImmutableEntry.of("Two", Int.valueOf(2)), ImmutableEntry.of("Three", Int.valueOf(3)),
            ImmutableEntry.of("Four", Int.valueOf(4)))));

    assertTrue(map.entrySet()
        .retainAll(FastList.newListWith(ImmutableEntry.of("One", Int.valueOf(1)),
            ImmutableEntry.of("Three", Int.valueOf(3)),
            ImmutableEntry.of("Four", Int.valueOf(4)))));
    assertEquals(
        UnifiedMap.newWithKeysValues("One", Int.valueOf(1), "Three", Int.valueOf(3)), map);

    MutableMapIterable<Int, Int> integers = newMapWithKeysValues(Int.valueOf(1), Int.valueOf(1),
        Int.valueOf(2), Int.valueOf(2), Int.valueOf(3), Int.valueOf(3));
    Int copy = new Int(1);
    assertTrue(integers.entrySet().retainAll(mList(ImmutableEntry.of(copy, copy))));
    assertEquals(iMap(copy, copy), integers);
    assertNotSame(copy, Iterate.getOnly(integers.entrySet()).getKey());
    assertNotSame(copy, Iterate.getOnly(integers.entrySet()).getValue());
  }

  @Test
  void clearEntrySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    map.entrySet().clear();
    Verify.assertEmpty(map);
  }

  @Test
  void entrySetEqualsAndHashCode() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Verify.assertEqualsAndHashCode(UnifiedSet.newSetWith(ImmutableEntry.of("One", 1),
        ImmutableEntry.of("Two", 2), ImmutableEntry.of("Three", 3)), map.entrySet());
  }

  @Test
  void removeFromKeySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertFalse(map.keySet().remove("Four"));

    assertTrue(map.keySet().remove("Two"));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  void removeNullFromKeySet() {
    if (newMap() instanceof ConcurrentMap || newMap() instanceof SortedMap) {
      return;
    }

    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertFalse(map.keySet().remove(null));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Two", 2, "Three", 3), map);
    map.put(null, 4);
    assertTrue(map.keySet().remove(null));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Two", 2, "Three", 3), map);
  }

  @Test
  void removeAllFromKeySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertFalse(map.keySet().removeAll(FastList.newListWith("Four")));

    assertTrue(map.keySet().removeAll(FastList.newListWith("Two", "Four")));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  void retainAllFromKeySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertFalse(map.keySet().retainAll(FastList.newListWith("One", "Two", "Three", "Four")));

    assertTrue(map.keySet().retainAll(FastList.newListWith("One", "Three")));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  void clearKeySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    map.keySet().clear();
    Verify.assertEmpty(map);
  }

  @Test
  void keySetEqualsAndHashCode() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Verify.assertEqualsAndHashCode(UnifiedSet.newSetWith("One", "Two", "Three"), map.keySet());
  }

  @Test
  void keySetToArray() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    MutableList<String> expected = FastList.newListWith("One", "Two", "Three").toSortedList();
    Set<String> keySet = map.keySet();
    assertEquals(expected, FastList.newListWith(keySet.toArray()).toSortedList());
    assertEquals(expected,
        FastList.newListWith(keySet.toArray(new String[keySet.size()])).toSortedList());
  }

  @Test
  void removeFromValues() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertFalse(map.values().remove(4));

    assertTrue(map.values().remove(2));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  void removeNullFromValues() {
    if (newMap() instanceof ConcurrentMap) {
      return;
    }

    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertFalse(map.values().remove(null));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Two", 2, "Three", 3), map);
    map.put("Four", null);
    assertTrue(map.values().remove(null));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Two", 2, "Three", 3), map);
  }

  @Test
  void removeAllFromValues() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertFalse(map.values().removeAll(FastList.newListWith(4)));

    assertTrue(map.values().removeAll(FastList.newListWith(2, 4)));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  void retainAllFromValues() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertFalse(map.values().retainAll(FastList.newListWith(1, 2, 3, 4)));

    assertTrue(map.values().retainAll(FastList.newListWith(1, 3)));
    assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  void put() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "One", 2, "Two");
    assertNull(map.put(3, "Three"));
    assertEquals(UnifiedMap.newWithKeysValues(1, "One", 2, "Two", 3, "Three"), map);

    ImmutableList<Integer> key1 = Lists.immutable.with((Integer) null);
    ImmutableList<Integer> key2 = Lists.immutable.with((Integer) null);
    Object value1 = new Object();
    Object value2 = new Object();
    MutableMapIterable<ImmutableList<Integer>, Object> map2 = newMapWithKeyValue(key1, value1);
    Object previousValue = map2.put(key2, value2);
    assertSame(value1, previousValue);
    assertSame(key1, map2.keysView().getFirst());
  }

  @Test
  void putAll() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "One", 2, "2");
    MutableMapIterable<Integer, String> toAdd = newMapWithKeysValues(2, "Two", 3, "Three");

    map.putAll(toAdd);
    Verify.assertSize(3, map);
    Verify.assertContainsAllKeyValues(map, 1, "One", 2, "Two", 3, "Three");

    // Testing JDK map
    MutableMapIterable<Integer, String> map2 = newMapWithKeysValues(1, "One", 2, "2");
    HashMap<Integer, String> hashMapToAdd = new HashMap<>(toAdd);
    map2.putAll(hashMapToAdd);
    Verify.assertSize(3, map2);
    Verify.assertContainsAllKeyValues(map2, 1, "One", 2, "Two", 3, "Three");
  }

  @Test
  void removeKey() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "Two");

    assertEquals("1", map.removeKey(1));
    Verify.assertSize(1, map);
    Verify.denyContainsKey(1, map);

    assertNull(map.removeKey(42));
    Verify.assertSize(1, map);

    assertEquals("Two", map.removeKey(2));
    Verify.assertEmpty(map);
  }

  @Test
  void removeAllKeys() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "Two", 3, "Three");

    assertThrows(NullPointerException.class, () -> map.removeAllKeys(null));
    assertFalse(map.removeAllKeys(Sets.mutable.with(4)));
    assertFalse(map.removeAllKeys(Sets.mutable.with(4, 5, 6)));
    assertFalse(map.removeAllKeys(Sets.mutable.with(4, 5, 6, 7, 8, 9)));

    assertTrue(map.removeAllKeys(Sets.mutable.with(1)));
    Verify.denyContainsKey(1, map);
    assertTrue(map.removeAllKeys(Sets.mutable.with(3, 4, 5, 6, 7)));
    Verify.denyContainsKey(3, map);

    map.putAll(Maps.mutable.with(4, "Four", 5, "Five", 6, "Six", 7, "Seven"));
    assertTrue(map.removeAllKeys(Sets.mutable.with(2, 3, 9, 10)));
    Verify.denyContainsKey(2, map);
    assertTrue(map.removeAllKeys(Sets.mutable.with(5, 3, 7, 8, 9)));
    assertEquals(Maps.mutable.with(4, "Four", 6, "Six"), map);
  }

  @Test
  void removeIf() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "Two");

    assertFalse(map.removeIf(Predicates2.alwaysFalse()));
    assertEquals(newMapWithKeysValues(1, "1", 2, "Two"), map);
    assertTrue(map.removeIf(Predicates2.alwaysTrue()));
    Verify.assertEmpty(map);

    map.putAll(Maps.mutable.with(1, "One", 2, "TWO", 3, "THREE", 4, "four"));
    map.putAll(Maps.mutable.with(5, "Five", 6, "Six", 7, "Seven", 8, "Eight"));
    assertTrue(map.removeIf((each, value) -> each % 2 == 0 && value.length() < 4));
    Verify.denyContainsKey(2, map);
    Verify.denyContainsKey(6, map);
    MutableMapIterable<Integer, String> expected =
        newMapWithKeysValues(1, "One", 3, "THREE", 4, "four", 5, "Five");
    expected.put(7, "Seven");
    expected.put(8, "Eight");
    assertEquals(expected, map);

    assertTrue(map.removeIf((each, value) -> each % 2 != 0 && value.equals("THREE")));
    Verify.denyContainsKey(3, map);
    Verify.assertSize(5, map);

    assertTrue(map.removeIf((each, value) -> each % 2 != 0));
    assertFalse(map.removeIf((each, value) -> each % 2 != 0));
    assertEquals(newMapWithKeysValues(4, "four", 8, "Eight"), map);
  }

  @Test
  void getIfAbsentPut() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertNull(map.get(4));
    assertEquals("4", map.getIfAbsentPut(4, new PassThruFunction0<>("4")));
    assertEquals("3", map.getIfAbsentPut(3, new PassThruFunction0<>("3")));
    Verify.assertContainsKeyValue(4, "4", map);
  }

  @Test
  void getIfAbsentPutValue() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertNull(map.get(4));
    assertEquals("4", map.getIfAbsentPut(4, "4"));
    assertEquals("3", map.getIfAbsentPut(3, "5"));
    Verify.assertContainsKeyValue(1, "1", map);
    Verify.assertContainsKeyValue(2, "2", map);
    Verify.assertContainsKeyValue(3, "3", map);
    Verify.assertContainsKeyValue(4, "4", map);
  }

  @Test
  void getIfAbsentPutWithKey() {
    MutableMapIterable<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2, 3, 3);
    assertNull(map.get(4));
    assertEquals(Integer.valueOf(4),
        map.getIfAbsentPutWithKey(4, Functions.getIntegerPassThru()));
    assertEquals(Integer.valueOf(3),
        map.getIfAbsentPutWithKey(3, Functions.getIntegerPassThru()));
    Verify.assertContainsKeyValue(Integer.valueOf(4), Integer.valueOf(4), map);
  }

  @Test
  void getIfAbsentPutWith() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertNull(map.get(4));
    assertEquals("4", map.getIfAbsentPutWith(4, String::valueOf, 4));
    assertEquals("3", map.getIfAbsentPutWith(3, String::valueOf, 3));
    Verify.assertContainsKeyValue(4, "4", map);
  }

  @Test
  void getIfAbsentPut_block_throws() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertThrows(RuntimeException.class, () -> map.getIfAbsentPut(4, () -> {
      throw new RuntimeException();
    }));
    assertEquals(UnifiedMap.newWithKeysValues(1, "1", 2, "2", 3, "3"), map);
  }

  @Test
  void getIfAbsentPutWith_block_throws() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    assertThrows(RuntimeException.class, () -> map.getIfAbsentPutWith(4, object -> {
      throw new RuntimeException();
    }, null));
    assertEquals(UnifiedMap.newWithKeysValues(1, "1", 2, "2", 3, "3"), map);
  }

  @Test
  void getKeysAndGetValues() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Verify.assertContainsAll(map.keySet(), 1, 2, 3);
    Verify.assertContainsAll(map.values(), "1", "2", "3");
  }

  @Test
  void newEmpty() {
    MutableMapIterable<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    Verify.assertEmpty(map.newEmpty());
  }

  @Test
  void keysAndValues_toString() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2");
    Verify.assertContains(map.keySet().toString(), FastList.newListWith("[1, 2]", "[2, 1]"));
    Verify.assertContains(map.values().toString(), FastList.newListWith("[1, 2]", "[2, 1]"));
    Verify.assertContains(map.keysView().toString(), FastList.newListWith("[1, 2]", "[2, 1]"));
    Verify.assertContains(map.valuesView().toString(), FastList.newListWith("[1, 2]", "[2, 1]"));
  }

  @Test
  void keyPreservation() {
    Key key = new Key("key");

    Key duplicateKey1 = new Key("key");
    MapIterable<Key, Integer> map1 = newMapWithKeysValues(key, 1, duplicateKey1, 2);
    Verify.assertSize(1, map1);
    Verify.assertContainsKeyValue(key, 2, map1);
    assertSame(key, map1.keysView().getFirst());

    Key duplicateKey2 = new Key("key");
    MapIterable<Key, Integer> map2 =
        newMapWithKeysValues(key, 1, duplicateKey1, 2, duplicateKey2, 3);
    Verify.assertSize(1, map2);
    Verify.assertContainsKeyValue(key, 3, map2);
    assertSame(key, map1.keysView().getFirst());

    Key duplicateKey3 = new Key("key");
    MapIterable<Key, Integer> map3 =
        newMapWithKeysValues(key, 1, new Key("not a dupe"), 2, duplicateKey3, 3);
    Verify.assertSize(2, map3);
    Verify.assertContainsAllKeyValues(map3, key, 3, new Key("not a dupe"), 2);
    assertSame(key, map3.keysView().detect(key::equals));

    Key duplicateKey4 = new Key("key");
    MapIterable<Key, Integer> map4 = newMapWithKeysValues(key, 1, new Key("still not a dupe"), 2,
        new Key("me neither"), 3, duplicateKey4, 4);
    Verify.assertSize(3, map4);
    Verify.assertContainsAllKeyValues(map4, key, 4, new Key("still not a dupe"), 2,
        new Key("me neither"), 3);
    assertSame(key, map4.keysView().detect(key::equals));

    MapIterable<Key, Integer> map5 =
        newMapWithKeysValues(key, 1, duplicateKey1, 2, duplicateKey3, 3, duplicateKey4, 4);
    Verify.assertSize(1, map5);
    Verify.assertContainsKeyValue(key, 4, map5);
    assertSame(key, map5.keysView().getFirst());
  }

  @Test
  void asUnmodifiable() {
    assertThrows(UnsupportedOperationException.class,
        () -> newMapWithKeysValues(1, 1, 2, 2).asUnmodifiable().put(3, 3));
  }

  @Test
  void asSynchronized() {
    MapIterable<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2).asSynchronized();
    Verify.assertInstanceOf(AbstractSynchronizedMapIterable.class, map);
  }

  @Test
  void add() {
    MutableMapIterable<String, Integer> map = newMapWithKeyValue("A", 1);

    assertEquals(Integer.valueOf(1), map.add(Tuples.pair("A", 3)));
    assertNull(map.add(Tuples.pair("B", 2)));
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 3, "B", 2), map);
  }

  @Test
  void putPair() {
    MutableMapIterable<String, Integer> map = newMapWithKeyValue("A", 1);

    assertEquals(Integer.valueOf(1), map.putPair(Tuples.pair("A", 3)));
    assertNull(map.putPair(Tuples.pair("B", 2)));
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 3, "B", 2), map);
  }

  @Test
  void withKeyValue() {
    MutableMapIterable<String, Integer> map = newMapWithKeyValue("A", 1);

    MutableMapIterable<String, Integer> mapWith = map.withKeyValue("B", 2);
    assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 2), mapWith);

    mapWith.withKeyValue("A", 11);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 11, "B", 2), mapWith);
  }

  @Test
  void withMap() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    Map<String, Integer> simpleMap = Maps.mutable.with("B", 22, "C", 3);
    map.putAll(simpleMap);
    MutableMapIterable<String, Integer> mapWith = map.withMap(simpleMap);
    assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 22, "C", 3), mapWith);
  }

  @Test
  void withMapEmpty() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith = map.withMap(Maps.mutable.empty());
    assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 2), mapWith);
  }

  @Test
  void withMapTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    Map<String, Integer> simpleMap = Maps.mutable.with("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith = map.withMap(simpleMap);
    assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 2), mapWith);
  }

  @Test
  void withMapEmptyAndTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    MutableMapIterable<String, Integer> mapWith = map.withMap(Maps.mutable.empty());
    assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newMap(), mapWith);
  }

  @Test
  void withMapNull() {
    assertThrows(NullPointerException.class, () -> newMap().withMap(null));
  }

  @Test
  void withMapIterable() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> simpleMap = Maps.mutable.with("B", 22, "C", 3);
    map.putAll(simpleMap);
    MutableMapIterable<String, Integer> mapWith = map.withMapIterable(simpleMap);
    assertSame(map, mapWith);
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 22, "C", 3), mapWith);
  }

  @Test
  void withMapIterableEmpty() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith = map.withMapIterable(Maps.mutable.empty());
    assertSame(map, mapWith);
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 2), mapWith);
  }

  @Test
  void withMapIterableTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    MutableMapIterable<String, Integer> mapWith =
        map.withMapIterable(Maps.mutable.with("A", 1, "B", 2));
    assertSame(map, mapWith);
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 2), mapWith);
  }

  @Test
  void withMapIterableEmptyAndTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    MutableMapIterable<String, Integer> mapWith = map.withMapIterable(Maps.mutable.empty());
    assertSame(map, mapWith);
    Verify.assertMapsEqual(Maps.mutable.withMapIterable(map), mapWith);
  }

  @Test
  void withMapIterableNull() {
    assertThrows(NullPointerException.class, () -> newMap().withMapIterable(null));
  }

  @Test
  void putAllMapIterable() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> simpleMap = Maps.mutable.with("B", 22, "C", 3);
    map.putAllMapIterable(simpleMap);
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 22, "C", 3), map);
  }

  @Test
  void putAllMapIterableEmpty() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    map.putAllMapIterable(Maps.mutable.empty());
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 2), map);
  }

  @Test
  void putAllMapIterableTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    map.putAllMapIterable(Maps.mutable.with("A", 1, "B", 2));
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 2), map);
  }

  @Test
  void putAllMapIterableEmptyAndTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    map.putAllMapIterable(Maps.mutable.empty());
    Verify.assertMapsEqual(Maps.mutable.withMapIterable(map), map);
  }

  @Test
  void putAllMapIterableNull() {
    assertThrows(NullPointerException.class, () -> newMap().putAllMapIterable(null));
  }

  @Test
  void withAllKeyValues() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith =
        map.withAllKeyValues(FastList.newListWith(Tuples.pair("B", 22), Tuples.pair("C", 3)));
    assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 22, "C", 3), mapWith);
  }

  @Test
  void withAllKeyValueArguments() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith =
        map.withAllKeyValueArguments(Tuples.pair("B", 22), Tuples.pair("C", 3));
    assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 22, "C", 3), mapWith);
  }

  @Test
  void withoutKey() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWithout = map.withoutKey("B");
    assertSame(map, mapWithout);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1), mapWithout);
  }

  @Test
  void withoutAllKeys() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2, "C", 3);
    MutableMapIterable<String, Integer> mapWithout =
        map.withoutAllKeys(FastList.newListWith("A", "C"));
    assertSame(map, mapWithout);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("B", 2), mapWithout);
  }

  @Test
  void retainAllFromKeySet_null_collision() {
    if (newMap() instanceof ConcurrentMap || newMap() instanceof SortedMap) {
      return;
    }

    IntegerWithCast key = new IntegerWithCast(0);
    MutableMapIterable<IntegerWithCast, String> mutableMapIterable =
        newMapWithKeysValues(null, "Test 1", key, "Test 2");

    assertFalse(mutableMapIterable.keySet().retainAll(FastList.newListWith(key, null)));

    assertEquals(newMapWithKeysValues(null, "Test 1", key, "Test 2"), mutableMapIterable);
  }

  @Test
  void rehash_null_collision() {
    if (newMap() instanceof ConcurrentMap || newMap() instanceof SortedMap) {
      return;
    }
    MutableMapIterable<IntegerWithCast, String> mutableMapIterable = newMapWithKeyValue(null, null);

    for (int i = 0; i < 256; i++) {
      mutableMapIterable.put(new IntegerWithCast(i), String.valueOf(i));
    }
  }

  @Test
  void updateValue() {
    MutableMapIterable<Integer, Integer> map = newMap();
    Iterate.forEach(Interval.oneTo(1000),
        each -> map.updateValue(each % 10, () -> 0, integer -> integer + 1));
    assertEquals(Interval.zeroTo(9).toSet(), map.keySet());
    assertEquals(FastList.newList(Collections.nCopies(10, 100)),
        FastList.newList(map.values()));
  }

  @Test
  void updateValue_collisions() {
    MutableMapIterable<Integer, Integer> map = newMap();
    MutableList<Integer> list = Interval.oneTo(2000).toList().shuffleThis();
    Iterate.forEach(list, each -> map.updateValue(each % 1000, () -> 0, integer -> integer + 1));
    assertEquals(Interval.zeroTo(999).toSet(), map.keySet());
    assertEquals(FastList.newList(Collections.nCopies(1000, 2)), FastList.newList(map.values()),
        HashBag.newBag(map.values()).toStringOfItemToCount());
  }

  @Test
  void updateValueWith() {
    MutableMapIterable<Integer, Integer> map = newMap();
    Iterate.forEach(Interval.oneTo(1000),
        each -> map.updateValueWith(each % 10, () -> 0, (integer, parameter) -> {
          assertEquals("test", parameter);
          return integer + 1;
        }, "test"));
    assertEquals(Interval.zeroTo(9).toSet(), map.keySet());
    assertEquals(FastList.newList(Collections.nCopies(10, 100)),
        FastList.newList(map.values()));
  }

  @Test
  void updateValueWith_collisions() {
    MutableMapIterable<Integer, Integer> map = newMap();
    MutableList<Integer> list = Interval.oneTo(2000).toList().shuffleThis();
    Iterate.forEach(list,
        each -> map.updateValueWith(each % 1000, () -> 0, (integer, parameter) -> {
          assertEquals("test", parameter);
          return integer + 1;
        }, "test"));
    assertEquals(Interval.zeroTo(999).toSet(), map.keySet());
    assertEquals(FastList.newList(Collections.nCopies(1000, 2)), FastList.newList(map.values()),
        HashBag.newBag(map.values()).toStringOfItemToCount());
  }
}
