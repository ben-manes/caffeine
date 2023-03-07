/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */
package com.github.benmanes.caffeine.eclipse.acceptance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.test.Verify;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ported from Eclipse Collections 11.0.
 */
@SuppressWarnings({"CatchFail", "deprecation", "rawtypes", "ThreadPriorityCheck", "unchecked"})
public abstract class UnifiedMapAcceptanceTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(UnifiedMapAcceptanceTest.class);

  private static final Comparator<Map.Entry<CollidingInt, String>> ENTRY_COMPARATOR =
      (o1, o2) -> o1.getKey().compareTo(o2.getKey());

  private static final Comparator<String> VALUE_COMPARATOR =
      (o1, o2) -> Integer.parseInt(o1.substring(1)) - Integer.parseInt(o2.substring(1));

  public abstract <K, V> MutableMap<K, V> newMap();

  @Test
  public void forEachWithIndexWithChainedValues() {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, 3), createVal(i));
    }
    int[] intArray = new int[1];
    intArray[0] = -1;
    map.forEachWithIndex((value, index) -> {
      Assert.assertEquals(index, intArray[0] + 1);
      intArray[0] = index;
    });
  }

  private static String createVal(int i) {
    return "X" + i;
  }

  // todo: tests with null values
  // todo: keyset.removeAll(some collection where one of the keys is associated with null in the
  // map) == true
  // todo: entryset.add(key associated with null) == true
  // todo: entryset.contains(entry with null value) == true

  @Test
  public void unifiedMapWithCollisions() {
    assertUnifiedMapWithCollisions(0, 2);
    assertUnifiedMapWithCollisions(1, 2);
    assertUnifiedMapWithCollisions(2, 2);
    assertUnifiedMapWithCollisions(3, 2);
    assertUnifiedMapWithCollisions(4, 2);

    assertUnifiedMapWithCollisions(0, 4);
    assertUnifiedMapWithCollisions(1, 4);
    assertUnifiedMapWithCollisions(2, 4);
    assertUnifiedMapWithCollisions(3, 4);
    assertUnifiedMapWithCollisions(4, 4);

    assertUnifiedMapWithCollisions(0, 8);
    assertUnifiedMapWithCollisions(1, 8);
    assertUnifiedMapWithCollisions(2, 8);
    assertUnifiedMapWithCollisions(3, 8);
    assertUnifiedMapWithCollisions(4, 8);
  }

  private void assertUnifiedMapWithCollisions(int shift, int removeStride) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    for (int i = 0; i < size; i++) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }

    for (int i = 0; i < size; i += removeStride) {
      Assert.assertEquals(createVal(i), map.remove(new CollidingInt(i, shift)));
    }
    Verify.assertSize(size - size / removeStride, map);
    for (int i = 0; i < size; i++) {
      if (i % removeStride == 0) {
        Verify.assertNotContainsKey(new CollidingInt(i, shift), map);
        Assert.assertNull(map.get(new CollidingInt(i, shift)));
      } else {
        Verify.assertContainsKey(new CollidingInt(i, shift), map);
        Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
      }
    }
    for (int i = 0; i < size; i++) {
      map.remove(new CollidingInt(i, shift));
    }
    Verify.assertSize(0, map);
  }

  @Test
  public void unifiedMap() {
    MutableMap<Integer, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(i, createVal(i));
    }
    Verify.assertSize(size, map);
    for (int i = 0; i < size; i++) {
      Assert.assertTrue(map.containsKey(i));
      Assert.assertEquals(createVal(i), map.get(i));
    }

    for (int i = 0; i < size; i += 2) {
      Assert.assertEquals(createVal(i), map.remove(i));
    }
    Verify.assertSize(size / 2, map);
    for (int i = 1; i < size; i += 2) {
      Assert.assertTrue(map.containsKey(i));
      Assert.assertEquals(createVal(i), map.get(i));
    }
  }

  @Test
  public void unifiedMapClear() {
    assertUnifiedMapClear(0);
    assertUnifiedMapClear(1);
    assertUnifiedMapClear(2);
    assertUnifiedMapClear(3);
  }

  private void assertUnifiedMapClear(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    map.clear();
    Verify.assertSize(0, map);
    for (int i = 0; i < size; i++) {
      Verify.assertNotContainsKey(new CollidingInt(i, shift), map);
      Assert.assertNull(map.get(new CollidingInt(i, shift)));
    }
  }

  @Test
  public void unifiedMapForEachEntry() {
    assertUnifiedMapForEachEntry(0);
    assertUnifiedMapForEachEntry(1);
    assertUnifiedMapForEachEntry(2);
    assertUnifiedMapForEachEntry(3);
  }

  private void assertUnifiedMapForEachEntry(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    int[] count = new int[1];
    map.forEachKeyValue((key, value) -> {
      Assert.assertEquals(createVal(key.getValue()), value);
      count[0]++;
    });
    Assert.assertEquals(size, count[0]);
  }

  @Test
  public void unifiedMapForEachKey() {
    assertUnifiedMapForEachKey(0);
    assertUnifiedMapForEachKey(1);
    assertUnifiedMapForEachKey(2);
    assertUnifiedMapForEachKey(3);
  }

  private void assertUnifiedMapForEachKey(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    List<CollidingInt> keys = new ArrayList<>(size);
    map.forEachKey(keys::add);
    Verify.assertSize(size, keys);
    Collections.sort(keys);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(new CollidingInt(i, shift), keys.get(i));
    }
  }

  @Test
  public void unifiedMapForEachValue() {
    assertUnifiedMapForEachValue(0);
    assertUnifiedMapForEachValue(1);
    assertUnifiedMapForEachValue(2);
    assertUnifiedMapForEachValue(3);
  }

  private void assertUnifiedMapForEachValue(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    List<String> values = new ArrayList<>(size);
    map.forEachValue(values::add);
    Verify.assertSize(size, values);
    Collections.sort(values, VALUE_COMPARATOR);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(createVal(i), values.get(i));
    }
  }

  @Test
  public void equalsWithNullValue() {
    MutableMap<Integer, Integer> map1 = UnifiedMap.newWithKeysValues(1, null, 2, 2);
    MutableMap<Integer, Integer> map2 = UnifiedMap.newWithKeysValues(2, 2, 3, 3);
    Assert.assertNotEquals(map1, map2);
  }

  @Test
  public void unifiedMapEqualsAndHashCode() {
    assertUnifiedMapEqualsAndHashCode(0);
    assertUnifiedMapEqualsAndHashCode(1);
    assertUnifiedMapEqualsAndHashCode(2);
    assertUnifiedMapEqualsAndHashCode(3);
  }

  private void assertUnifiedMapEqualsAndHashCode(int shift) {
    MutableMap<CollidingInt, String> map1 = newMap();
    Map<CollidingInt, String> map2 = new HashMap<>();
    MutableMap<CollidingInt, String> map3 = newMap();
    MutableMap<CollidingInt, String> map4 = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map1.put(new CollidingInt(i, shift), createVal(i));
      map2.put(new CollidingInt(i, shift), createVal(i));
      map3.put(new CollidingInt(i, shift), createVal(i));
      map4.put(new CollidingInt(size - i - 1, shift), createVal(size - i - 1));
    }

    Assert.assertEquals(map2, map1);
    Assert.assertEquals(map1, map2);
    Assert.assertEquals(map2.hashCode(), map1.hashCode());
    Assert.assertEquals(map1, map3);
    Assert.assertEquals(map1.hashCode(), map3.hashCode());
    Assert.assertEquals(map2, map4);
    Assert.assertEquals(map4, map2);
    Assert.assertEquals(map2.hashCode(), map4.hashCode());

    Verify.assertSetsEqual(map2.entrySet(), map1.entrySet());
    Verify.assertSetsEqual(map1.entrySet(), map2.entrySet());
    Assert.assertEquals(map2.entrySet().hashCode(), map1.entrySet().hashCode());
    Verify.assertSetsEqual(map1.entrySet(), map3.entrySet());
    Assert.assertEquals(map1.entrySet().hashCode(), map3.entrySet().hashCode());
    Verify.assertSetsEqual(map2.entrySet(), map4.entrySet());
    Verify.assertSetsEqual(map4.entrySet(), map2.entrySet());
    Assert.assertEquals(map2.entrySet().hashCode(), map4.entrySet().hashCode());

    Verify.assertSetsEqual(map2.keySet(), map1.keySet());
    Verify.assertSetsEqual(map1.keySet(), map2.keySet());
    Assert.assertEquals(map2.keySet().hashCode(), map1.keySet().hashCode());
    Verify.assertSetsEqual(map1.keySet(), map3.keySet());
    Assert.assertEquals(map1.keySet().hashCode(), map3.keySet().hashCode());
    Verify.assertSetsEqual(map2.keySet(), map4.keySet());
    Verify.assertSetsEqual(map4.keySet(), map2.keySet());
    Assert.assertEquals(map2.keySet().hashCode(), map4.keySet().hashCode());
  }

  @Test
  public void unifiedMapPutAll() {
    assertUnifiedMapPutAll(0);
    assertUnifiedMapPutAll(1);
    assertUnifiedMapPutAll(2);
    assertUnifiedMapPutAll(3);
  }

  private void assertUnifiedMapPutAll(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    MutableMap<CollidingInt, String> newMap = UnifiedMap.newMap(size);
    newMap.putAll(map);

    Verify.assertSize(size, newMap);
    for (int i = 0; i < size; i++) {
      Verify.assertContainsKey(new CollidingInt(i, shift), newMap);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), newMap);
    }
  }

  @Test
  public void unifiedMapPutAllWithHashMap() {
    assertUnifiedMapPutAllWithHashMap(0);
    assertUnifiedMapPutAllWithHashMap(1);
    assertUnifiedMapPutAllWithHashMap(2);
    assertUnifiedMapPutAllWithHashMap(3);
  }

  private void assertUnifiedMapPutAllWithHashMap(int shift) {
    Map<CollidingInt, String> map = new HashMap<>();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    MutableMap<CollidingInt, String> newMap = UnifiedMap.newMap(size);
    newMap.putAll(map);

    Verify.assertSize(size, newMap);
    for (int i = 0; i < size; i++) {
      Verify.assertContainsKey(new CollidingInt(i, shift), newMap);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), newMap);
    }
  }

  @Test
  public void unifiedMapReplace() {
    assertUnifiedMapReplace(0);
    assertUnifiedMapReplace(1);
    assertUnifiedMapReplace(2);
    assertUnifiedMapReplace(3);
  }

  private void assertUnifiedMapReplace(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), "Y" + i);
    }
    Verify.assertSize(size, map);
    for (int i = 0; i < size; i++) {
      Assert.assertEquals("Y" + i, map.get(new CollidingInt(i, shift)));
    }
  }

  @Test
  public void unifiedMapContainsValue() {
    runUnifiedMapContainsValue(0);
    runUnifiedMapContainsValue(1);
    runUnifiedMapContainsValue(2);
    runUnifiedMapContainsValue(3);
  }

  private void runUnifiedMapContainsValue(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 1000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    for (int i = 0; i < size; i++) {
      Assert.assertTrue(map.containsValue(createVal(i)));
    }
  }

  @Test
  public void unifiedMapKeySet() {
    runUnifiedMapKeySet(0);
    runUnifiedMapKeySet(1);
    runUnifiedMapKeySet(2);
    runUnifiedMapKeySet(3);
  }

  private void runUnifiedMapKeySet(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<CollidingInt> keySet = map.keySet();
    Verify.assertSize(size, keySet);
    for (int i = 0; i < size; i++) {
      Verify.assertContains(new CollidingInt(i, shift), keySet);
    }

    for (int i = 0; i < size; i += 2) {
      Assert.assertTrue(keySet.remove(new CollidingInt(i, shift)));
    }
    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, keySet);

    for (int i = 1; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(new CollidingInt(i, shift), keySet);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapKeySetRetainAll() {
    runUnifiedMapKeySetRetainAll(0);
    runUnifiedMapKeySetRetainAll(1);
    runUnifiedMapKeySetRetainAll(2);
    runUnifiedMapKeySetRetainAll(3);
  }

  private void runUnifiedMapKeySetRetainAll(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    List<CollidingInt> toRetain = new ArrayList<>();

    int size = 10_000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
      if (i % 2 == 0) {
        toRetain.add(new CollidingInt(i, shift));
      }
    }
    Verify.assertSize(size, map);
    Set<CollidingInt> keySet = map.keySet();
    Assert.assertTrue(keySet.containsAll(toRetain));

    Assert.assertTrue(keySet.retainAll(toRetain));
    Assert.assertTrue(keySet.containsAll(toRetain));

    Assert.assertFalse(keySet.retainAll(toRetain)); // a second call should not modify the set

    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, keySet);

    for (int i = 0; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(new CollidingInt(i, shift), keySet);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapKeySetRemoveAll() {
    runUnifiedMapKeySetRemoveAll(0);
    runUnifiedMapKeySetRemoveAll(1);
    runUnifiedMapKeySetRemoveAll(2);
    runUnifiedMapKeySetRemoveAll(3);
  }

  private void runUnifiedMapKeySetRemoveAll(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    List<CollidingInt> toRemove = new ArrayList<>();

    int size = 10_000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
      if (i % 2 == 0) {
        toRemove.add(new CollidingInt(i, shift));
      }
    }
    Verify.assertSize(size, map);
    Set<CollidingInt> keySet = map.keySet();

    Assert.assertTrue(keySet.removeAll(toRemove));

    Assert.assertFalse(keySet.removeAll(toRemove)); // a second call should not modify the set

    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, keySet);

    for (int i = 1; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(new CollidingInt(i, shift), keySet);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapKeySetToArray() {
    runUnifiedMapKeySetToArray(0);
    runUnifiedMapKeySetToArray(1);
    runUnifiedMapKeySetToArray(2);
    runUnifiedMapKeySetToArray(3);
  }

  private void runUnifiedMapKeySetToArray(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<CollidingInt> keySet = map.keySet();

    Object[] keys = keySet.toArray();
    Arrays.sort(keys);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(new CollidingInt(i, shift), keys[i]);
    }
  }

  @Test
  public void unifiedMapKeySetIterator() {
    runUnifiedMapKeySetIterator(0);
    runUnifiedMapKeySetIterator(1);
    runUnifiedMapKeySetIterator(2);
    runUnifiedMapKeySetIterator(3);
  }

  private void runUnifiedMapKeySetIterator(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<CollidingInt> keySet = map.keySet();

    CollidingInt[] keys = new CollidingInt[size];
    int count = 0;
    for (CollidingInt collidingInt : keySet) {
      keys[count++] = collidingInt;
    }
    Arrays.sort(keys);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(new CollidingInt(i, shift), keys[i]);
    }
  }

  @Test
  public void unifiedMapKeySetIteratorRemove() {
    runUnifiedMapKeySetIteratorRemove(0, 2);
    runUnifiedMapKeySetIteratorRemove(1, 2);
    runUnifiedMapKeySetIteratorRemove(2, 2);
    runUnifiedMapKeySetIteratorRemove(3, 2);

    runUnifiedMapKeySetIteratorRemove(0, 3);
    runUnifiedMapKeySetIteratorRemove(1, 3);
    runUnifiedMapKeySetIteratorRemove(2, 3);
    runUnifiedMapKeySetIteratorRemove(3, 3);

    runUnifiedMapKeySetIteratorRemove(0, 4);
    runUnifiedMapKeySetIteratorRemove(1, 4);
    runUnifiedMapKeySetIteratorRemove(2, 4);
    runUnifiedMapKeySetIteratorRemove(3, 4);
  }

  private void runUnifiedMapKeySetIteratorRemove(int shift, int removeStride) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<CollidingInt> keySet = map.keySet();

    int count = 0;
    for (Iterator<CollidingInt> it = keySet.iterator(); it.hasNext();) {
      CollidingInt key = it.next();
      count++;
      if (key.getValue() % removeStride == 0) {
        it.remove();
      }
    }
    Assert.assertEquals(size, count);

    for (int i = 0; i < size; i++) {
      if (i % removeStride != 0) {
        Assert.assertTrue(
            "map contains " + i + "for shift " + shift + " and remove stride " + removeStride,
            map.containsKey(new CollidingInt(i, shift)));
        Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
      }
    }
  }

  @Test
  public void unifiedMapKeySetIteratorRemoveFlip() {
    runUnifiedMapKeySetIteratorRemoveFlip(0, 2);
    runUnifiedMapKeySetIteratorRemoveFlip(1, 2);
    runUnifiedMapKeySetIteratorRemoveFlip(2, 2);
    runUnifiedMapKeySetIteratorRemoveFlip(3, 2);

    runUnifiedMapKeySetIteratorRemoveFlip(0, 3);
    runUnifiedMapKeySetIteratorRemoveFlip(1, 3);
    runUnifiedMapKeySetIteratorRemoveFlip(2, 3);
    runUnifiedMapKeySetIteratorRemoveFlip(3, 3);

    runUnifiedMapKeySetIteratorRemoveFlip(0, 4);
    runUnifiedMapKeySetIteratorRemoveFlip(1, 4);
    runUnifiedMapKeySetIteratorRemoveFlip(2, 4);
    runUnifiedMapKeySetIteratorRemoveFlip(3, 4);
  }

  private void runUnifiedMapKeySetIteratorRemoveFlip(int shift, int removeStride) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<CollidingInt> keySet = map.keySet();

    int count = 0;
    for (Iterator<CollidingInt> it = keySet.iterator(); it.hasNext();) {
      CollidingInt key = it.next();
      count++;
      if (key.getValue() % removeStride != 0) {
        it.remove();
      }
    }
    Assert.assertEquals(size, count);

    for (int i = 0; i < size; i++) {
      if (i % removeStride == 0) {
        Assert.assertTrue(
            "map contains " + i + "for shift " + shift + " and remove stride " + removeStride,
            map.containsKey(new CollidingInt(i, shift)));
        Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
      }
    }
  }

  // entry set tests

  @Test
  public void unifiedMapEntrySet() {
    runUnifiedMapEntrySet(0);
    runUnifiedMapEntrySet(1);
    runUnifiedMapEntrySet(2);
    runUnifiedMapEntrySet(3);
  }

  private void runUnifiedMapEntrySet(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<Map.Entry<CollidingInt, String>> entrySet = map.entrySet();
    Verify.assertSize(size, entrySet);
    for (int i = 0; i < size; i++) {
      Verify.assertContains(new Entry(new CollidingInt(i, shift), createVal(i)), entrySet);
    }

    for (int i = 0; i < size; i += 2) {
      Assert.assertTrue(entrySet.remove(new Entry(new CollidingInt(i, shift), createVal(i))));
    }
    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, entrySet);

    for (int i = 1; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(new Entry(new CollidingInt(i, shift), createVal(i)), entrySet);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapEntrySetRetainAll() {
    runUnifiedMapEntrySetRetainAll(0);
    runUnifiedMapEntrySetRetainAll(1);
    runUnifiedMapEntrySetRetainAll(2);
    runUnifiedMapEntrySetRetainAll(3);
  }

  private void runUnifiedMapEntrySetRetainAll(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    List<Entry> toRetain = new ArrayList<>();

    int size = 10_000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
      if (i % 2 == 0) {
        toRetain.add(new Entry(new CollidingInt(i, shift), createVal(i)));
      }
    }
    Verify.assertSize(size, map);
    Set<Map.Entry<CollidingInt, String>> entrySet = map.entrySet();
    Assert.assertTrue(entrySet.containsAll(toRetain));

    Assert.assertTrue(entrySet.retainAll(toRetain));
    Assert.assertTrue(entrySet.containsAll(toRetain));

    Assert.assertFalse(entrySet.retainAll(toRetain)); // a second call should not modify the set

    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, entrySet);

    for (int i = 0; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(new Entry(new CollidingInt(i, shift), createVal(i)), entrySet);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapEntrySetRemoveAll() {
    runUnifiedMapEntrySetRemoveAll(0);
    runUnifiedMapEntrySetRemoveAll(1);
    runUnifiedMapEntrySetRemoveAll(2);
    runUnifiedMapEntrySetRemoveAll(3);
  }

  private void runUnifiedMapEntrySetRemoveAll(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    List<Entry> toRemove = new ArrayList<>();

    int size = 10_000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
      if (i % 2 == 0) {
        toRemove.add(new Entry(new CollidingInt(i, shift), createVal(i)));
      }
    }
    Verify.assertSize(size, map);
    Set<Map.Entry<CollidingInt, String>> entrySet = map.entrySet();

    Assert.assertTrue(entrySet.removeAll(toRemove));

    Assert.assertFalse(entrySet.removeAll(toRemove)); // a second call should not modify the set

    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, entrySet);

    for (int i = 1; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(new Entry(new CollidingInt(i, shift), createVal(i)), entrySet);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapEntrySetToArray() {
    runUnifiedMapEntrySetToArray(0);
    runUnifiedMapEntrySetToArray(1);
    runUnifiedMapEntrySetToArray(2);
    runUnifiedMapEntrySetToArray(3);
  }

  private void runUnifiedMapEntrySetToArray(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<Map.Entry<CollidingInt, String>> entrySet = map.entrySet();

    Map.Entry<CollidingInt, String>[] entries = entrySet.toArray(new Map.Entry[0]);
    Arrays.sort(entries, ENTRY_COMPARATOR);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(new Entry(new CollidingInt(i, shift), createVal(i)), entries[i]);
    }
  }

  @Test
  public void unifiedMapEntrySetIterator() {
    runUnifiedMapEntrySetIterator(0);
    runUnifiedMapEntrySetIterator(1);
    runUnifiedMapEntrySetIterator(2);
    runUnifiedMapEntrySetIterator(3);
  }

  private void runUnifiedMapEntrySetIterator(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<Map.Entry<CollidingInt, String>> entrySet = map.entrySet();

    Map.Entry<CollidingInt, String>[] entries = new Map.Entry[size];
    int count = 0;
    for (Map.Entry<CollidingInt, String> collidingIntStringEntry : entrySet) {
      entries[count++] = collidingIntStringEntry;
    }
    Arrays.sort(entries, ENTRY_COMPARATOR);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(new Entry(new CollidingInt(i, shift), createVal(i)), entries[i]);
    }
  }

  @Test
  public void unifiedMapEntrySetIteratorSetValue() {
    runUnifiedMapEntrySetIteratorSetValue(0);
    runUnifiedMapEntrySetIteratorSetValue(1);
    runUnifiedMapEntrySetIteratorSetValue(2);
    runUnifiedMapEntrySetIteratorSetValue(3);
  }

  private void runUnifiedMapEntrySetIteratorSetValue(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<Map.Entry<CollidingInt, String>> entrySet = map.entrySet();
    for (Map.Entry<CollidingInt, String> entry : entrySet) {
      CollidingInt key = entry.getKey();
      entry.setValue("Y" + key.getValue());
    }

    Map.Entry<CollidingInt, String>[] entries = new Map.Entry[size];
    int count = 0;
    for (Map.Entry<CollidingInt, String> collidingIntStringEntry : entrySet) {
      entries[count++] = collidingIntStringEntry;
    }
    Arrays.sort(entries, ENTRY_COMPARATOR);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(new Entry(new CollidingInt(i, shift), "Y" + i), entries[i]);
    }
  }

  @Test
  public void unifiedMapEntrySetIteratorRemove() {
    runUnifiedMapEntrySetIteratorRemove(0, 2);
    runUnifiedMapEntrySetIteratorRemove(1, 2);
    runUnifiedMapEntrySetIteratorRemove(2, 2);
    runUnifiedMapEntrySetIteratorRemove(3, 2);

    runUnifiedMapEntrySetIteratorRemove(0, 3);
    runUnifiedMapEntrySetIteratorRemove(1, 3);
    runUnifiedMapEntrySetIteratorRemove(2, 3);
    runUnifiedMapEntrySetIteratorRemove(3, 3);

    runUnifiedMapEntrySetIteratorRemove(0, 4);
    runUnifiedMapEntrySetIteratorRemove(1, 4);
    runUnifiedMapEntrySetIteratorRemove(2, 4);
    runUnifiedMapEntrySetIteratorRemove(3, 4);
  }

  private void runUnifiedMapEntrySetIteratorRemove(int shift, int removeStride) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<Map.Entry<CollidingInt, String>> entrySet = map.entrySet();

    int count = 0;
    for (Iterator<Map.Entry<CollidingInt, String>> it = entrySet.iterator(); it.hasNext();) {
      CollidingInt entry = it.next().getKey();
      count++;
      if (entry.getValue() % removeStride == 0) {
        it.remove();
      }
    }
    Assert.assertEquals(size, count);

    for (int i = 0; i < size; i++) {
      if (i % removeStride != 0) {
        Assert.assertTrue(
            "map contains " + i + "for shift " + shift + " and remove stride " + removeStride,
            map.containsKey(new CollidingInt(i, shift)));
        Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
      }
    }
  }

  @Test
  public void unifiedMapEntrySetIteratorRemoveFlip() {
    runUnifiedMapEntrySetIteratorRemoveFlip(0, 2);
    runUnifiedMapEntrySetIteratorRemoveFlip(1, 2);
    runUnifiedMapEntrySetIteratorRemoveFlip(2, 2);
    runUnifiedMapEntrySetIteratorRemoveFlip(3, 2);

    runUnifiedMapEntrySetIteratorRemoveFlip(0, 3);
    runUnifiedMapEntrySetIteratorRemoveFlip(1, 3);
    runUnifiedMapEntrySetIteratorRemoveFlip(2, 3);
    runUnifiedMapEntrySetIteratorRemoveFlip(3, 3);

    runUnifiedMapEntrySetIteratorRemoveFlip(0, 4);
    runUnifiedMapEntrySetIteratorRemoveFlip(1, 4);
    runUnifiedMapEntrySetIteratorRemoveFlip(2, 4);
    runUnifiedMapEntrySetIteratorRemoveFlip(3, 4);
  }

  private void runUnifiedMapEntrySetIteratorRemoveFlip(int shift, int removeStride) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Set<Map.Entry<CollidingInt, String>> entrySet = map.entrySet();

    int count = 0;
    for (Iterator<Map.Entry<CollidingInt, String>> it = entrySet.iterator(); it.hasNext();) {
      CollidingInt entry = it.next().getKey();
      count++;
      if (entry.getValue() % removeStride != 0) {
        it.remove();
      }
    }
    Assert.assertEquals(size, count);

    for (int i = 0; i < size; i++) {
      if (i % removeStride == 0) {
        Assert.assertTrue(
            "map contains " + i + "for shift " + shift + " and remove stride " + removeStride,
            map.containsKey(new CollidingInt(i, shift)));
        Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
      }
    }
  }

  // values collection

  @Test
  public void unifiedMapValues() {
    runUnifiedMapValues(0);
    runUnifiedMapValues(1);
    runUnifiedMapValues(2);
    runUnifiedMapValues(3);
  }

  private void runUnifiedMapValues(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 1000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Collection<String> values = map.values();
    Assert.assertEquals(size, values.size());
    for (int i = 0; i < size; i++) {
      Verify.assertContains(createVal(i), values);
    }

    for (int i = 0; i < size; i += 2) {
      Assert.assertTrue(values.remove(createVal(i)));
    }
    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, values);

    for (int i = 1; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(createVal(i), values);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapValuesRetainAll() {
    runUnifiedMapValuesRetainAll(0);
    runUnifiedMapValuesRetainAll(1);
    runUnifiedMapValuesRetainAll(2);
    runUnifiedMapValuesRetainAll(3);
  }

  private void runUnifiedMapValuesRetainAll(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    List<String> toRetain = new ArrayList<>();

    int size = 1000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
      if (i % 2 == 0) {
        toRetain.add(createVal(i));
      }
    }
    Verify.assertSize(size, map);
    Collection<String> values = map.values();
    Assert.assertTrue(values.containsAll(toRetain));

    Assert.assertTrue(values.retainAll(toRetain));
    Assert.assertTrue(values.containsAll(toRetain));

    Assert.assertFalse(values.retainAll(toRetain)); // a second call should not modify the set

    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, values);

    for (int i = 0; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(createVal(i), values);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapValuesRemoveAll() {
    runUnifiedMapValuesRemoveAll(0);
    runUnifiedMapValuesRemoveAll(1);
    runUnifiedMapValuesRemoveAll(2);
    runUnifiedMapValuesRemoveAll(3);
  }

  private void runUnifiedMapValuesRemoveAll(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    List<String> toRemove = new ArrayList<>();

    int size = 1000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
      if (i % 2 == 0) {
        toRemove.add(createVal(i));
      }
    }
    Verify.assertSize(size, map);
    Collection<String> values = map.values();

    Assert.assertTrue(values.removeAll(toRemove));

    Assert.assertFalse(values.removeAll(toRemove)); // a second call should not modify the set

    Verify.assertSize(size / 2, map);
    Verify.assertSize(size / 2, values);

    for (int i = 1; i < size; i += 2) {
      Verify.assertContainsKey(new CollidingInt(i, shift), map);
      Verify.assertContains(createVal(i), values);
      Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
    }
  }

  @Test
  public void unifiedMapValuesToArray() {
    runUnifiedMapValuesToArray(0);
    runUnifiedMapValuesToArray(1);
    runUnifiedMapValuesToArray(2);
    runUnifiedMapValuesToArray(3);
  }

  private void runUnifiedMapValuesToArray(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 1000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Collection<String> values = map.values();

    String[] entries = values.toArray(new String[0]);
    Arrays.sort(entries, VALUE_COMPARATOR);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(createVal(i), entries[i]);
    }
  }

  @Test
  public void unifiedMapValuesIterator() {
    runUnifiedMapValuesIterator(0);
    runUnifiedMapValuesIterator(1);
    runUnifiedMapValuesIterator(2);
    runUnifiedMapValuesIterator(3);
  }

  private void runUnifiedMapValuesIterator(int shift) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 1000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Collection<String> values = map.values();

    String[] valuesArray = new String[size];
    int count = 0;
    for (String value : values) {
      valuesArray[count++] = value;
    }
    Arrays.sort(valuesArray, VALUE_COMPARATOR);

    for (int i = 0; i < size; i++) {
      Assert.assertEquals(createVal(i), valuesArray[i]);
    }
  }

  @Test
  public void unifiedMapValuesIteratorRemove() {
    runUnifiedMapValuesIteratorRemove(0, 2);
    runUnifiedMapValuesIteratorRemove(1, 2);
    runUnifiedMapValuesIteratorRemove(2, 2);
    runUnifiedMapValuesIteratorRemove(3, 2);

    runUnifiedMapValuesIteratorRemove(0, 3);
    runUnifiedMapValuesIteratorRemove(1, 3);
    runUnifiedMapValuesIteratorRemove(2, 3);
    runUnifiedMapValuesIteratorRemove(3, 3);

    runUnifiedMapValuesIteratorRemove(0, 4);
    runUnifiedMapValuesIteratorRemove(1, 4);
    runUnifiedMapValuesIteratorRemove(2, 4);
    runUnifiedMapValuesIteratorRemove(3, 4);
  }

  private void runUnifiedMapValuesIteratorRemove(int shift, int removeStride) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Collection<String> values = map.values();

    int count = 0;
    for (Iterator<String> it = values.iterator(); it.hasNext();) {
      String value = it.next();
      int x = Integer.parseInt(value.substring(1));
      count++;
      if (x % removeStride == 0) {
        it.remove();
      }
    }
    Assert.assertEquals(size, count);

    for (int i = 0; i < size; i++) {
      if (i % removeStride != 0) {
        Assert.assertTrue(
            "map contains " + i + "for shift " + shift + " and remove stride " + removeStride,
            map.containsKey(new CollidingInt(i, shift)));
        Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
      }
    }
  }

  @Test
  public void unifiedMapValuesIteratorRemoveFlip() {
    runUnifiedMapValuesIteratorRemoveFlip(0, 2);
    runUnifiedMapValuesIteratorRemoveFlip(1, 2);
    runUnifiedMapValuesIteratorRemoveFlip(2, 2);
    runUnifiedMapValuesIteratorRemoveFlip(3, 2);

    runUnifiedMapValuesIteratorRemoveFlip(0, 3);
    runUnifiedMapValuesIteratorRemoveFlip(1, 3);
    runUnifiedMapValuesIteratorRemoveFlip(2, 3);
    runUnifiedMapValuesIteratorRemoveFlip(3, 3);

    runUnifiedMapValuesIteratorRemoveFlip(0, 4);
    runUnifiedMapValuesIteratorRemoveFlip(1, 4);
    runUnifiedMapValuesIteratorRemoveFlip(2, 4);
    runUnifiedMapValuesIteratorRemoveFlip(3, 4);
  }

  private void runUnifiedMapValuesIteratorRemoveFlip(int shift, int removeStride) {
    MutableMap<CollidingInt, String> map = newMap();

    int size = 100000;
    for (int i = 0; i < size; i++) {
      map.put(new CollidingInt(i, shift), createVal(i));
    }
    Verify.assertSize(size, map);
    Collection<String> values = map.values();

    int count = 0;
    for (Iterator<String> it = values.iterator(); it.hasNext();) {
      String value = it.next();
      int x = Integer.parseInt(value.substring(1));
      count++;
      if (x % removeStride != 0) {
        it.remove();
      }
    }
    Assert.assertEquals(size, count);

    for (int i = 0; i < size; i++) {
      if (i % removeStride == 0) {
        Assert.assertTrue(
            "map contains " + i + "for shift " + shift + " and remove stride " + removeStride,
            map.containsKey(new CollidingInt(i, shift)));
        Verify.assertContainsKeyValue(new CollidingInt(i, shift), createVal(i), map);
      }
    }
  }

  public void perfTestUnifiedMapGet() {
    for (int i = 1000000; i > 10; i /= 10) {
      runGetTest(newMap(), "Unified Map", i);
    }
  }

  public void perfTestJdkHashMapGet() {
    for (int i = 1000000; i > 10; i /= 10) {
      runGetTest(new HashMap<>(), "JDK HashMap", i);
    }
  }

  public void perfTestUnifiedMapCollidingGet() {
    runCollidingGetTest(newMap(), "Unified Map", 1);
    runCollidingGetTest(newMap(), "Unified Map", 2);
    runCollidingGetTest(newMap(), "Unified Map", 3);
  }

  public void perfTestJdkHashMapCollidingGet() {
    runCollidingGetTest(new HashMap<>(), "JDK HashMap", 1);
    runCollidingGetTest(new HashMap<>(), "JDK HashMap", 2);
    runCollidingGetTest(new HashMap<>(), "JDK HashMap", 3);
  }

  private void runGetTest(Map<CollidingInt, String> map, String mapName, int size) {
    Integer[] keys = createMap((Map<Object, String>) (Map<?, ?>) map, size);
    sleep(100L);
    int n = 10000000 / size;
    int max = 4;
    for (int i = 0; i < max; i++) {
      long startTime = System.nanoTime();
      runMapGet(map, keys, n);
      long runTimes = System.nanoTime() - startTime;
      LOGGER.info("{} get: {} ns per get on map size {}", mapName,
          (double) runTimes / (double) n / size, size);
    }
    map = null;
    System.gc();
    Thread.yield();
    System.gc();
    Thread.yield();
  }

  private static Integer[] createMap(Map<Object, String> map, int size) {
    Integer[] keys = new Integer[size];
    for (int i = 0; i < size; i++) {
      keys[i] = i;
      map.put(i, createVal(i));
    }
    Verify.assertSize(size, map);
    return keys;
  }

  private void runMapGet(Map<CollidingInt, String> map, Object[] keys, int n) {
    for (int i = 0; i < n; i++) {
      for (Object key : keys) {
        map.get(key);
      }
    }
  }

  private void runCollidingGetTest(Map<CollidingInt, String> map, String mapName, int shift) {
    int size = 100000;
    Object[] keys = createCollidingMap(map, size, shift);
    sleep(100L);
    int n = 100;
    int max = 5;
    for (int i = 0; i < max; i++) {
      long startTime = System.nanoTime();
      runMapGet(map, keys, n);
      long runTimes = System.nanoTime() - startTime;
      LOGGER.info("{} with {} collisions. get: {} ns per get", mapName, 1 << shift,
          (double) runTimes / (double) n / size);
    }
  }

  private static CollidingInt[] createCollidingMap(Map<CollidingInt, String> map, int size,
      int shift) {
    CollidingInt[] keys = new CollidingInt[size];
    for (int i = 0; i < size; i++) {
      keys[i] = new CollidingInt(i, shift);
      map.put(keys[i], createVal(i));
    }
    Assert.assertEquals(size, map.size());
    return keys;
  }

  @SuppressWarnings("PreferJavaTimeOverload")
  private void sleep(long millis) {
    long now = System.currentTimeMillis();
    long target = now + millis;
    while (now < target) {
      try {
        Thread.sleep(target - now);
      } catch (InterruptedException ignored) {
        Assert.fail("why were we interrupted?");
      }
      now = System.currentTimeMillis();
    }
  }

  public static final class Entry implements Map.Entry<CollidingInt, String> {
    private final CollidingInt key;
    private String value;

    private Entry(CollidingInt key, String value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public CollidingInt getKey() {
      return key;
    }

    @Override
    public String getValue() {
      return value;
    }

    @Override
    public String setValue(String value) {
      String ret = value;
      this.value = value;
      return ret;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Map.Entry)) {
        return false;
      }

      Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;

      if (!Objects.equals(key, entry.getKey())) {
        return false;
      }
      return Objects.equals(value, entry.getValue());
    }

    @Override
    public int hashCode() {
      return key == null ? 0 : key.hashCode();
    }
  }

  @Test
  public void unifiedMapToString() {
    MutableMap<Object, Object> map = UnifiedMap.newWithKeysValues(1, "One", 2, "Two");
    Verify.assertContains("1=One", map.toString());
    Verify.assertContains("2=Two", map.toString());
    map.put("value is 'self'", map);
    Verify.assertContains("value is 'self'=(this Map)", map.toString());
  }
}
