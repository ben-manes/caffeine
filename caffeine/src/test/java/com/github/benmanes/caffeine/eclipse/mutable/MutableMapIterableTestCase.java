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
import org.junit.Assert;
import org.junit.Test;

import com.github.benmanes.caffeine.testing.Int;

/**
 * Abstract JUnit TestCase for {@link MutableMapIterable}s.
 *
 * Ported from Eclipse Collections 11.0.
 */
@SuppressWarnings({"all", "deprecation", "unchecked"})
public abstract class MutableMapIterableTestCase extends MapIterableTestCase {
  @Override
  protected abstract <K, V> MutableMapIterable<K, V> newMap();

  @Override
  protected abstract <K, V> MutableMapIterable<K, V> newMapWithKeyValue(K key, V value);

  @Override
  protected abstract <K, V> MutableMapIterable<K, V> newMapWithKeysValues(K key1, V value1, K key2,
      V value2);

  @Override
  protected abstract <K, V> MutableMapIterable<K, V> newMapWithKeysValues(K key1, V value1, K key2,
      V value2, K key3, V value3);

  @Override
  protected abstract <K, V> MutableMapIterable<K, V> newMapWithKeysValues(K key1, V value1, K key2,
      V value2, K key3, V value3, K key4, V value4);

  @Test
  public void toImmutable() {
    MutableMapIterable<Integer, String> map = newMapWithKeyValue(1, "One");
    ImmutableMapIterable<Integer, String> immutable = map.toImmutable();
    Assert.assertEquals(Maps.immutable.with(1, "One"), immutable);
  }

  @Test
  public void clear() {
    MutableMapIterable<Integer, Object> map = newMapWithKeysValues(1, "One", 2, "Two", 3, "Three");
    map.clear();
    Verify.assertEmpty(map);

    MutableMapIterable<Object, Object> map2 = newMap();
    map2.clear();
    Verify.assertEmpty(map2);
  }

  @Test
  public void removeObject() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    map.remove("Two");
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  public void removeFromEntrySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertTrue(map.entrySet().remove(ImmutableEntry.of("Two", 2)));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);

    Assert.assertFalse(map.entrySet().remove(ImmutableEntry.of("Four", 4)));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);

    Assert.assertFalse(map.entrySet().remove(null));
  }

  @Test
  public void removeAllFromEntrySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertTrue(map.entrySet().removeAll(
        FastList.newListWith(ImmutableEntry.of("One", 1), ImmutableEntry.of("Three", 3))));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("Two", 2), map);

    Assert
        .assertFalse(map.entrySet().removeAll(FastList.newListWith(ImmutableEntry.of("Four", 4))));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("Two", 2), map);

    Assert.assertFalse(map.entrySet().remove(null));
  }

  @Test
  public void retainAllFromEntrySet() {
    MutableMapIterable<String, Int> map =
        newMapWithKeysValues("One", Int.valueOf(1), "Two", Int.valueOf(2), "Three", Int.valueOf(3));
    Assert.assertFalse(map.entrySet()
        .retainAll(FastList.newListWith(ImmutableEntry.of("One", Int.valueOf(1)),
            ImmutableEntry.of("Two", Int.valueOf(2)), ImmutableEntry.of("Three", Int.valueOf(3)),
            ImmutableEntry.of("Four", Int.valueOf(4)))));

    Assert.assertTrue(map.entrySet()
        .retainAll(FastList.newListWith(ImmutableEntry.of("One", Int.valueOf(1)),
            ImmutableEntry.of("Three", Int.valueOf(3)),
            ImmutableEntry.of("Four", Int.valueOf(4)))));
    Assert.assertEquals(
        UnifiedMap.newWithKeysValues("One", Int.valueOf(1), "Three", Int.valueOf(3)), map);

    MutableMapIterable<Int, Int> integers = newMapWithKeysValues(Int.valueOf(1), Int.valueOf(1),
        Int.valueOf(2), Int.valueOf(2), Int.valueOf(3), Int.valueOf(3));
    Int copy = new Int(1);
    Assert.assertTrue(integers.entrySet().retainAll(mList(ImmutableEntry.of(copy, copy))));
    Assert.assertEquals(iMap(copy, copy), integers);
    Assert.assertNotSame(copy, Iterate.getOnly(integers.entrySet()).getKey());
    Assert.assertNotSame(copy, Iterate.getOnly(integers.entrySet()).getValue());
  }

  @Test
  public void clearEntrySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    map.entrySet().clear();
    Verify.assertEmpty(map);
  }

  @Test
  public void entrySetEqualsAndHashCode() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Verify.assertEqualsAndHashCode(UnifiedSet.newSetWith(ImmutableEntry.of("One", 1),
        ImmutableEntry.of("Two", 2), ImmutableEntry.of("Three", 3)), map.entrySet());
  }

  @Test
  public void removeFromKeySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertFalse(map.keySet().remove("Four"));

    Assert.assertTrue(map.keySet().remove("Two"));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  public void removeNullFromKeySet() {
    if (newMap() instanceof ConcurrentMap || newMap() instanceof SortedMap) {
      return;
    }

    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertFalse(map.keySet().remove(null));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Two", 2, "Three", 3), map);
    map.put(null, 4);
    Assert.assertTrue(map.keySet().remove(null));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Two", 2, "Three", 3), map);
  }

  @Test
  public void removeAllFromKeySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertFalse(map.keySet().removeAll(FastList.newListWith("Four")));

    Assert.assertTrue(map.keySet().removeAll(FastList.newListWith("Two", "Four")));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  public void retainAllFromKeySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertFalse(map.keySet().retainAll(FastList.newListWith("One", "Two", "Three", "Four")));

    Assert.assertTrue(map.keySet().retainAll(FastList.newListWith("One", "Three")));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  public void clearKeySet() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    map.keySet().clear();
    Verify.assertEmpty(map);
  }

  @Test
  public void keySetEqualsAndHashCode() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Verify.assertEqualsAndHashCode(UnifiedSet.newSetWith("One", "Two", "Three"), map.keySet());
  }

  @Test
  public void keySetToArray() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    MutableList<String> expected = FastList.newListWith("One", "Two", "Three").toSortedList();
    Set<String> keySet = map.keySet();
    Assert.assertEquals(expected, FastList.newListWith(keySet.toArray()).toSortedList());
    Assert.assertEquals(expected,
        FastList.newListWith(keySet.toArray(new String[keySet.size()])).toSortedList());
  }

  @Test
  public void removeFromValues() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertFalse(map.values().remove(4));

    Assert.assertTrue(map.values().remove(2));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  public void removeNullFromValues() {
    if (newMap() instanceof ConcurrentMap) {
      return;
    }

    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertFalse(map.values().remove(null));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Two", 2, "Three", 3), map);
    map.put("Four", null);
    Assert.assertTrue(map.values().remove(null));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Two", 2, "Three", 3), map);
  }

  @Test
  public void removeAllFromValues() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertFalse(map.values().removeAll(FastList.newListWith(4)));

    Assert.assertTrue(map.values().removeAll(FastList.newListWith(2, 4)));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  public void retainAllFromValues() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Assert.assertFalse(map.values().retainAll(FastList.newListWith(1, 2, 3, 4)));

    Assert.assertTrue(map.values().retainAll(FastList.newListWith(1, 3)));
    Assert.assertEquals(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Test
  public void put() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "One", 2, "Two");
    Assert.assertNull(map.put(3, "Three"));
    Assert.assertEquals(UnifiedMap.newWithKeysValues(1, "One", 2, "Two", 3, "Three"), map);

    ImmutableList<Integer> key1 = Lists.immutable.with((Integer) null);
    ImmutableList<Integer> key2 = Lists.immutable.with((Integer) null);
    Object value1 = new Object();
    Object value2 = new Object();
    MutableMapIterable<ImmutableList<Integer>, Object> map2 = newMapWithKeyValue(key1, value1);
    Object previousValue = map2.put(key2, value2);
    Assert.assertSame(value1, previousValue);
    Assert.assertSame(key1, map2.keysView().getFirst());
  }

  @Test
  public void putAll() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "One", 2, "2");
    MutableMapIterable<Integer, String> toAdd = newMapWithKeysValues(2, "Two", 3, "Three");

    map.putAll(toAdd);
    Verify.assertSize(3, map);
    Verify.assertContainsAllKeyValues(map, 1, "One", 2, "Two", 3, "Three");

    // Testing JDK map
    MutableMapIterable<Integer, String> map2 = newMapWithKeysValues(1, "One", 2, "2");
    HashMap<Integer, String> hashMaptoAdd = new HashMap<>(toAdd);
    map2.putAll(hashMaptoAdd);
    Verify.assertSize(3, map2);
    Verify.assertContainsAllKeyValues(map2, 1, "One", 2, "Two", 3, "Three");
  }

  @Test
  public void removeKey() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "Two");

    Assert.assertEquals("1", map.removeKey(1));
    Verify.assertSize(1, map);
    Verify.denyContainsKey(1, map);

    Assert.assertNull(map.removeKey(42));
    Verify.assertSize(1, map);

    Assert.assertEquals("Two", map.removeKey(2));
    Verify.assertEmpty(map);
  }

  @Test
  public void removeAllKeys() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "Two", 3, "Three");

    Assert.assertThrows(NullPointerException.class, () -> map.removeAllKeys(null));
    Assert.assertFalse(map.removeAllKeys(Sets.mutable.with(4)));
    Assert.assertFalse(map.removeAllKeys(Sets.mutable.with(4, 5, 6)));
    Assert.assertFalse(map.removeAllKeys(Sets.mutable.with(4, 5, 6, 7, 8, 9)));

    Assert.assertTrue(map.removeAllKeys(Sets.mutable.with(1)));
    Verify.denyContainsKey(1, map);
    Assert.assertTrue(map.removeAllKeys(Sets.mutable.with(3, 4, 5, 6, 7)));
    Verify.denyContainsKey(3, map);

    map.putAll(Maps.mutable.with(4, "Four", 5, "Five", 6, "Six", 7, "Seven"));
    Assert.assertTrue(map.removeAllKeys(Sets.mutable.with(2, 3, 9, 10)));
    Verify.denyContainsKey(2, map);
    Assert.assertTrue(map.removeAllKeys(Sets.mutable.with(5, 3, 7, 8, 9)));
    Assert.assertEquals(Maps.mutable.with(4, "Four", 6, "Six"), map);
  }

  @Test
  public void removeIf() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "Two");

    Assert.assertFalse(map.removeIf(Predicates2.alwaysFalse()));
    Assert.assertEquals(newMapWithKeysValues(1, "1", 2, "Two"), map);
    Assert.assertTrue(map.removeIf(Predicates2.alwaysTrue()));
    Verify.assertEmpty(map);

    map.putAll(Maps.mutable.with(1, "One", 2, "TWO", 3, "THREE", 4, "four"));
    map.putAll(Maps.mutable.with(5, "Five", 6, "Six", 7, "Seven", 8, "Eight"));
    Assert.assertTrue(map.removeIf((each, value) -> each % 2 == 0 && value.length() < 4));
    Verify.denyContainsKey(2, map);
    Verify.denyContainsKey(6, map);
    MutableMapIterable<Integer, String> expected =
        newMapWithKeysValues(1, "One", 3, "THREE", 4, "four", 5, "Five");
    expected.put(7, "Seven");
    expected.put(8, "Eight");
    Assert.assertEquals(expected, map);

    Assert.assertTrue(map.removeIf((each, value) -> each % 2 != 0 && value.equals("THREE")));
    Verify.denyContainsKey(3, map);
    Verify.assertSize(5, map);

    Assert.assertTrue(map.removeIf((each, value) -> each % 2 != 0));
    Assert.assertFalse(map.removeIf((each, value) -> each % 2 != 0));
    Assert.assertEquals(newMapWithKeysValues(4, "four", 8, "Eight"), map);
  }

  @Test
  public void getIfAbsentPut() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Assert.assertNull(map.get(4));
    Assert.assertEquals("4", map.getIfAbsentPut(4, new PassThruFunction0<>("4")));
    Assert.assertEquals("3", map.getIfAbsentPut(3, new PassThruFunction0<>("3")));
    Verify.assertContainsKeyValue(4, "4", map);
  }

  @Test
  public void getIfAbsentPutValue() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Assert.assertNull(map.get(4));
    Assert.assertEquals("4", map.getIfAbsentPut(4, "4"));
    Assert.assertEquals("3", map.getIfAbsentPut(3, "5"));
    Verify.assertContainsKeyValue(1, "1", map);
    Verify.assertContainsKeyValue(2, "2", map);
    Verify.assertContainsKeyValue(3, "3", map);
    Verify.assertContainsKeyValue(4, "4", map);
  }

  @Test
  public void getIfAbsentPutWithKey() {
    MutableMapIterable<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2, 3, 3);
    Assert.assertNull(map.get(4));
    Assert.assertEquals(Integer.valueOf(4),
        map.getIfAbsentPutWithKey(4, Functions.getIntegerPassThru()));
    Assert.assertEquals(Integer.valueOf(3),
        map.getIfAbsentPutWithKey(3, Functions.getIntegerPassThru()));
    Verify.assertContainsKeyValue(Integer.valueOf(4), Integer.valueOf(4), map);
  }

  @Test
  public void getIfAbsentPutWith() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Assert.assertNull(map.get(4));
    Assert.assertEquals("4", map.getIfAbsentPutWith(4, String::valueOf, 4));
    Assert.assertEquals("3", map.getIfAbsentPutWith(3, String::valueOf, 3));
    Verify.assertContainsKeyValue(4, "4", map);
  }

  @Test
  public void getIfAbsentPut_block_throws() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Assert.assertThrows(RuntimeException.class, () -> map.getIfAbsentPut(4, () -> {
      throw new RuntimeException();
    }));
    Assert.assertEquals(UnifiedMap.newWithKeysValues(1, "1", 2, "2", 3, "3"), map);
  }

  @Test
  public void getIfAbsentPutWith_block_throws() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Assert.assertThrows(RuntimeException.class, () -> map.getIfAbsentPutWith(4, object -> {
      throw new RuntimeException();
    }, null));
    Assert.assertEquals(UnifiedMap.newWithKeysValues(1, "1", 2, "2", 3, "3"), map);
  }

  @Test
  public void getKeysAndGetValues() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2", 3, "3");
    Verify.assertContainsAll(map.keySet(), 1, 2, 3);
    Verify.assertContainsAll(map.values(), "1", "2", "3");
  }

  @Test
  public void newEmpty() {
    MutableMapIterable<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    Verify.assertEmpty(map.newEmpty());
  }

  @Test
  public void keysAndValues_toString() {
    MutableMapIterable<Integer, String> map = newMapWithKeysValues(1, "1", 2, "2");
    Verify.assertContains(map.keySet().toString(), FastList.newListWith("[1, 2]", "[2, 1]"));
    Verify.assertContains(map.values().toString(), FastList.newListWith("[1, 2]", "[2, 1]"));
    Verify.assertContains(map.keysView().toString(), FastList.newListWith("[1, 2]", "[2, 1]"));
    Verify.assertContains(map.valuesView().toString(), FastList.newListWith("[1, 2]", "[2, 1]"));
  }

  @Test
  public void keyPreservation() {
    Key key = new Key("key");

    Key duplicateKey1 = new Key("key");
    MapIterable<Key, Integer> map1 = newMapWithKeysValues(key, 1, duplicateKey1, 2);
    Verify.assertSize(1, map1);
    Verify.assertContainsKeyValue(key, 2, map1);
    Assert.assertSame(key, map1.keysView().getFirst());

    Key duplicateKey2 = new Key("key");
    MapIterable<Key, Integer> map2 =
        newMapWithKeysValues(key, 1, duplicateKey1, 2, duplicateKey2, 3);
    Verify.assertSize(1, map2);
    Verify.assertContainsKeyValue(key, 3, map2);
    Assert.assertSame(key, map1.keysView().getFirst());

    Key duplicateKey3 = new Key("key");
    MapIterable<Key, Integer> map3 =
        newMapWithKeysValues(key, 1, new Key("not a dupe"), 2, duplicateKey3, 3);
    Verify.assertSize(2, map3);
    Verify.assertContainsAllKeyValues(map3, key, 3, new Key("not a dupe"), 2);
    Assert.assertSame(key, map3.keysView().detect(key::equals));

    Key duplicateKey4 = new Key("key");
    MapIterable<Key, Integer> map4 = newMapWithKeysValues(key, 1, new Key("still not a dupe"), 2,
        new Key("me neither"), 3, duplicateKey4, 4);
    Verify.assertSize(3, map4);
    Verify.assertContainsAllKeyValues(map4, key, 4, new Key("still not a dupe"), 2,
        new Key("me neither"), 3);
    Assert.assertSame(key, map4.keysView().detect(key::equals));

    MapIterable<Key, Integer> map5 =
        newMapWithKeysValues(key, 1, duplicateKey1, 2, duplicateKey3, 3, duplicateKey4, 4);
    Verify.assertSize(1, map5);
    Verify.assertContainsKeyValue(key, 4, map5);
    Assert.assertSame(key, map5.keysView().getFirst());
  }

  @Test
  public void asUnmodifiable() {
    Assert.assertThrows(UnsupportedOperationException.class,
        () -> newMapWithKeysValues(1, 1, 2, 2).asUnmodifiable().put(3, 3));
  }

  @Test
  public void asSynchronized() {
    MapIterable<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2).asSynchronized();
    Verify.assertInstanceOf(AbstractSynchronizedMapIterable.class, map);
  }

  @Test
  public void add() {
    MutableMapIterable<String, Integer> map = newMapWithKeyValue("A", 1);

    Assert.assertEquals(Integer.valueOf(1), map.add(Tuples.pair("A", 3)));
    Assert.assertNull(map.add(Tuples.pair("B", 2)));
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 3, "B", 2), map);
  }

  @Test
  public void putPair() {
    MutableMapIterable<String, Integer> map = newMapWithKeyValue("A", 1);

    Assert.assertEquals(Integer.valueOf(1), map.putPair(Tuples.pair("A", 3)));
    Assert.assertNull(map.putPair(Tuples.pair("B", 2)));
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 3, "B", 2), map);
  }

  @Test
  public void withKeyValue() {
    MutableMapIterable<String, Integer> map = newMapWithKeyValue("A", 1);

    MutableMapIterable<String, Integer> mapWith = map.withKeyValue("B", 2);
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 2), mapWith);

    mapWith.withKeyValue("A", 11);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 11, "B", 2), mapWith);
  }

  @Test
  public void withMap() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    Map<String, Integer> simpleMap = Maps.mutable.with("B", 22, "C", 3);
    map.putAll(simpleMap);
    MutableMapIterable<String, Integer> mapWith = map.withMap(simpleMap);
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 22, "C", 3), mapWith);
  }

  @Test
  public void withMapEmpty() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith = map.withMap(Maps.mutable.empty());
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 2), mapWith);
  }

  @Test
  public void withMapTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    Map<String, Integer> simpleMap = Maps.mutable.with("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith = map.withMap(simpleMap);
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 2), mapWith);
  }

  @Test
  public void withMapEmptyAndTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    MutableMapIterable<String, Integer> mapWith = map.withMap(Maps.mutable.empty());
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newMap(), mapWith);
  }

  @Test
  public void withMapNull() {
    Assert.assertThrows(NullPointerException.class, () -> newMap().withMap(null));
  }

  @Test
  public void withMapIterable() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> simpleMap = Maps.mutable.with("B", 22, "C", 3);
    map.putAll(simpleMap);
    MutableMapIterable<String, Integer> mapWith = map.withMapIterable(simpleMap);
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 22, "C", 3), mapWith);
  }

  @Test
  public void withMapIterableEmpty() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith = map.withMapIterable(Maps.mutable.empty());
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 2), mapWith);
  }

  @Test
  public void withMapIterableTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    MutableMapIterable<String, Integer> mapWith =
        map.withMapIterable(Maps.mutable.with("A", 1, "B", 2));
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 2), mapWith);
  }

  @Test
  public void withMapIterableEmptyAndTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    MutableMapIterable<String, Integer> mapWith = map.withMapIterable(Maps.mutable.empty());
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(Maps.mutable.withMapIterable(map), mapWith);
  }

  @Test
  public void withMapIterableNull() {
    Assert.assertThrows(NullPointerException.class, () -> newMap().withMapIterable(null));
  }

  @Test
  public void putAllMapIterable() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> simpleMap = Maps.mutable.with("B", 22, "C", 3);
    map.putAllMapIterable(simpleMap);
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 22, "C", 3), map);
  }

  @Test
  public void putAllMapIterableEmpty() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    map.putAllMapIterable(Maps.mutable.empty());
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 2), map);
  }

  @Test
  public void putAllMapIterableTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    map.putAllMapIterable(Maps.mutable.with("A", 1, "B", 2));
    Verify.assertMapsEqual(Maps.mutable.with("A", 1, "B", 2), map);
  }

  @Test
  public void putAllMapIterableEmptyAndTargetEmpty() {
    MutableMapIterable<String, Integer> map = newMap();
    map.putAllMapIterable(Maps.mutable.empty());
    Verify.assertMapsEqual(Maps.mutable.withMapIterable(map), map);
  }

  @Test
  public void putAllMapIterableNull() {
    Assert.assertThrows(NullPointerException.class, () -> newMap().putAllMapIterable(null));
  }

  @Test
  public void withAllKeyValues() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith =
        map.withAllKeyValues(FastList.newListWith(Tuples.pair("B", 22), Tuples.pair("C", 3)));
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 22, "C", 3), mapWith);
  }

  @Test
  public void withAllKeyValueArguments() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWith =
        map.withAllKeyValueArguments(Tuples.pair("B", 22), Tuples.pair("C", 3));
    Assert.assertSame(map, mapWith);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1, "B", 22, "C", 3), mapWith);
  }

  @Test
  public void withoutKey() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2);
    MutableMapIterable<String, Integer> mapWithout = map.withoutKey("B");
    Assert.assertSame(map, mapWithout);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("A", 1), mapWithout);
  }

  @Test
  public void withoutAllKeys() {
    MutableMapIterable<String, Integer> map = newMapWithKeysValues("A", 1, "B", 2, "C", 3);
    MutableMapIterable<String, Integer> mapWithout =
        map.withoutAllKeys(FastList.newListWith("A", "C"));
    Assert.assertSame(map, mapWithout);
    Verify.assertMapsEqual(UnifiedMap.newWithKeysValues("B", 2), mapWithout);
  }

  @Test
  public void retainAllFromKeySet_null_collision() {
    if (newMap() instanceof ConcurrentMap || newMap() instanceof SortedMap) {
      return;
    }

    IntegerWithCast key = new IntegerWithCast(0);
    MutableMapIterable<IntegerWithCast, String> mutableMapIterable =
        newMapWithKeysValues(null, "Test 1", key, "Test 2");

    Assert.assertFalse(mutableMapIterable.keySet().retainAll(FastList.newListWith(key, null)));

    Assert.assertEquals(newMapWithKeysValues(null, "Test 1", key, "Test 2"), mutableMapIterable);
  }

  @Test
  public void rehash_null_collision() {
    if (newMap() instanceof ConcurrentMap || newMap() instanceof SortedMap) {
      return;
    }
    MutableMapIterable<IntegerWithCast, String> mutableMapIterable = newMapWithKeyValue(null, null);

    for (int i = 0; i < 256; i++) {
      mutableMapIterable.put(new IntegerWithCast(i), String.valueOf(i));
    }
  }

  @Test
  public void updateValue() {
    MutableMapIterable<Integer, Integer> map = newMap();
    Iterate.forEach(Interval.oneTo(1000),
        each -> map.updateValue(each % 10, () -> 0, integer -> integer + 1));
    Assert.assertEquals(Interval.zeroTo(9).toSet(), map.keySet());
    Assert.assertEquals(FastList.newList(Collections.nCopies(10, 100)),
        FastList.newList(map.values()));
  }

  @Test
  public void updateValue_collisions() {
    MutableMapIterable<Integer, Integer> map = newMap();
    MutableList<Integer> list = Interval.oneTo(2000).toList().shuffleThis();
    Iterate.forEach(list, each -> map.updateValue(each % 1000, () -> 0, integer -> integer + 1));
    Assert.assertEquals(Interval.zeroTo(999).toSet(), map.keySet());
    Assert.assertEquals(HashBag.newBag(map.values()).toStringOfItemToCount(),
        FastList.newList(Collections.nCopies(1000, 2)), FastList.newList(map.values()));
  }

  @Test
  public void updateValueWith() {
    MutableMapIterable<Integer, Integer> map = newMap();
    Iterate.forEach(Interval.oneTo(1000),
        each -> map.updateValueWith(each % 10, () -> 0, (integer, parameter) -> {
          Assert.assertEquals("test", parameter);
          return integer + 1;
        }, "test"));
    Assert.assertEquals(Interval.zeroTo(9).toSet(), map.keySet());
    Assert.assertEquals(FastList.newList(Collections.nCopies(10, 100)),
        FastList.newList(map.values()));
  }

  @Test
  public void updateValueWith_collisions() {
    MutableMapIterable<Integer, Integer> map = newMap();
    MutableList<Integer> list = Interval.oneTo(2000).toList().shuffleThis();
    Iterate.forEach(list,
        each -> map.updateValueWith(each % 1000, () -> 0, (integer, parameter) -> {
          Assert.assertEquals("test", parameter);
          return integer + 1;
        }, "test"));
    Assert.assertEquals(Interval.zeroTo(999).toSet(), map.keySet());
    Assert.assertEquals(HashBag.newBag(map.values()).toStringOfItemToCount(),
        FastList.newList(Collections.nCopies(1000, 2)), FastList.newList(map.values()));
  }
}
