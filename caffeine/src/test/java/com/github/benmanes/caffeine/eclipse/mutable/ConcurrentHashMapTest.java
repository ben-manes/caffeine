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

import static com.github.benmanes.caffeine.testing.ConcurrentTestHarness.executor;
import static org.eclipse.collections.impl.factory.Iterables.iSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.eclipse.collections.api.bag.MutableBag;
import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.function.Function3;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.eclipse.collections.api.map.MapIterable;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.api.partition.PartitionIterable;
import org.eclipse.collections.impl.bag.mutable.HashBag;
import org.eclipse.collections.impl.block.factory.Functions;
import org.eclipse.collections.impl.block.factory.IntegerPredicates;
import org.eclipse.collections.impl.block.factory.Predicates2;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.list.Interval;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.ConcurrentHashMap;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;
import org.eclipse.collections.impl.parallel.ParallelIterate;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;
import org.eclipse.collections.impl.test.Verify;
import org.eclipse.collections.impl.tuple.ImmutableEntry;
import org.jspecify.annotations.NullUnmarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * JUnit test for {@link ConcurrentHashMap}.
 *
 * Ported from Eclipse Collections 11.0.
 */
@NullUnmarked
@ParameterizedClass
@MethodSource("caches")
@SuppressWarnings({"all", "CanIgnoreReturnValueSuggester", "IdentityConversion", "unchecked"})
final class ConcurrentHashMapTest extends ConcurrentHashMapTestCase {
  public static final MutableMap<Integer, MutableBag<Integer>> SMALL_BAG_MUTABLE_MAP =
      Interval.oneTo(100).groupBy(each -> each % 10).toMap(HashBag::new);

  public <K, V> ConcurrentMutableMap<K, V> newMap(Map<K, V> other) {
    ConcurrentMutableMap<K, V> map = newMap();
    map.putAll(other);
    return map;
  }

  public <K, V> V getIfAbsentPut(ConcurrentMutableMap<K, V> map, K key,
      Function<? super K, ? extends V> factory) {
    return map.computeIfAbsent(key, factory);
  }

  public <K, V, P1, P2> V putIfAbsentGetIfPresent(ConcurrentMutableMap<K, V> map, K key,
      Function2<? super K, ? super V, ? extends K> keyTransformer,
      Function3<P1, P2, ? super K, ? extends V> factory, P1 param1, P2 param2) {
    boolean[] added = {false};
    V newValue = map.computeIfAbsent(keyTransformer.apply(key, null), k -> {
      added[0] = true;
      return factory.value(param1, param2, key);
    });
    return added[0] ? null : newValue;
  }

  @Test
  void doubleReverseTest() {
    FastList<String> source = FastList.newListWith("1", "2", "3");
    MutableList<String> expectedDoubleReverse =
        source.toReversed().collect(new Function<String, String>() {
          private String visited = "";

          @Override
          public String valueOf(String object) {
            return visited += object;
          }
        }).toReversed();
    assertEquals(FastList.newListWith("321", "32", "3"), expectedDoubleReverse);
    MutableList<String> expectedNormal = source.collect(new Function<String, String>() {
      private String visited = "";

      @Override
      public String valueOf(String object) {
        return visited += object;
      }
    });
    assertEquals(FastList.newListWith("1", "12", "123"), expectedNormal);
  }

  @Test
  void putIfAbsent() {
    ConcurrentMutableMap<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    assertEquals(Integer.valueOf(1), map.putIfAbsent(1, 1));
    assertNull(map.putIfAbsent(3, 3));
  }

  @Test
  void replace() {
    ConcurrentMutableMap<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    assertEquals(Integer.valueOf(1), map.replace(1, 7));
    assertEquals(Integer.valueOf(7), map.get(1));
    assertNull(map.replace(3, 3));
  }

  @Test
  void entrySetContains() {
    MutableMap<String, Integer> map = newMapWithKeysValues("One", Integer.valueOf(1), "Two",
        Integer.valueOf(2), "Three", Integer.valueOf(3));
    assertFalse(map.entrySet().contains(null));
    assertFalse(map.entrySet().contains(ImmutableEntry.of("Zero", Integer.valueOf(0))));
    assertTrue(map.entrySet().contains(ImmutableEntry.of("One", Integer.valueOf(1))));
  }

  @Test
  void entrySetRemove() {
    MutableMap<String, Integer> map = newMapWithKeysValues("One", Integer.valueOf(1), "Two",
        Integer.valueOf(2), "Three", Integer.valueOf(3));
    assertFalse(map.entrySet().remove(null));
    assertFalse(map.entrySet().remove(ImmutableEntry.of("Zero", Integer.valueOf(0))));
    assertTrue(map.entrySet().remove(ImmutableEntry.of("One", Integer.valueOf(1))));
  }

  @Test
  void replaceWithOldValue() {
    ConcurrentMutableMap<Integer, Integer> map = newMapWithKeysValues(1, 1, 2, 2);
    assertTrue(map.replace(1, 1, 7));
    assertEquals(Integer.valueOf(7), map.get(1));
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
    Verify.assertEqualsAndHashCode(UnifiedMap.newWithKeysValues("One", 1, "Three", 3), map);
  }

  @Override
  @Test
  void removeAllFromEntrySet() {
    MutableMap<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    assertTrue(map.entrySet().removeAll(
        FastList.newListWith(ImmutableEntry.of("One", 1), ImmutableEntry.of("Three", 3))));
    assertEquals(UnifiedMap.newWithKeysValues("Two", 2), map);

    assertFalse(map.entrySet().removeAll(FastList.newListWith(ImmutableEntry.of("Four", 4))));
    Verify.assertEqualsAndHashCode(UnifiedMap.newWithKeysValues("Two", 2), map);
  }

  @Override
  @Test
  void keySetEqualsAndHashCode() {
    MutableMap<String, Integer> map = newMapWithKeysValues("One", 1, "Two", 2, "Three", 3);
    Verify.assertEqualsAndHashCode(UnifiedSet.newSetWith("One", "Two", "Three"), map.keySet());
  }

  @Test
  void equalsEdgeCases() {
    assertNotEquals(newMap().withKeyValue(1, 1), newMap());
    assertNotEquals(newMap().withKeyValue(1, 1),
        newMap().withKeyValue(1, 1).withKeyValue(2, 2));
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
  @Test
  void withMapNull() {
    assertThrows(NullPointerException.class, () -> newMap().withMap(null));
  }

  @Test
  void parallelGroupByIntoConcurrentHashMap() {
    MutableMap<Integer, MutableBag<Integer>> actual = newMap();
    ParallelIterate.forEach(
        Interval.oneTo(100), each -> actual
            .getIfAbsentPut(each % 10, () -> HashBag.<Integer>newBag().asSynchronized()).add(each),
        10, executor);
    Verify.assertEqualsAndHashCode(SMALL_BAG_MUTABLE_MAP, actual);
  }

  @Test
  void concurrentPutGetPutAllRemoveContainsKeyContainsValueGetIfAbsentPutTest() {
    ConcurrentMutableMap<Integer, Integer> map1 = newMap();
    ConcurrentMutableMap<Integer, Integer> map2 = newMap();
    ParallelIterate.forEach(Interval.oneTo(100), each -> {
      map1.put(each, each);
      assertEquals(each, map1.get(each));
      map2.putAll(Maps.mutable.of(each, each));
      map1.remove(each);
      map1.putAll(Maps.mutable.of(each, each));
      assertEquals(each, map2.get(each));
      map2.remove(each);
      assertNull(map2.get(each));
      assertFalse(map2.containsValue(each));
      assertFalse(map2.containsKey(each));
      assertEquals(each, getIfAbsentPut(map2, each, Functions.getIntegerPassThru()));
      assertTrue(map2.containsValue(each));
      assertTrue(map2.containsKey(each));
      assertEquals(each, getIfAbsentPut(map2, each, Functions.getIntegerPassThru()));
      map2.remove(each);
      assertEquals(each,
          map2.getIfAbsentPutWith(each, Functions.getIntegerPassThru(), each));
      assertEquals(each,
          map2.getIfAbsentPutWith(each, Functions.getIntegerPassThru(), each));
      assertEquals(each, getIfAbsentPut(map2, each, Functions.getIntegerPassThru()));
    }, 1, executor);
    Verify.assertEqualsAndHashCode(map1, map2);
  }

  @Test
  void concurrentPutIfAbsentGetIfPresentPutTest() {
    ConcurrentMutableMap<Integer, Integer> map1 = newMap();
    ConcurrentMutableMap<Integer, Integer> map2 = newMap();
    ParallelIterate.forEach(Interval.oneTo(100), each -> {
      map1.put(each, each);
      map1.put(each, each);
      assertEquals(each, map1.get(each));
      map2.putAll(Maps.mutable.of(each, each));
      map2.putAll(Maps.mutable.of(each, each));
      map1.remove(each);
      assertNull(putIfAbsentGetIfPresent(map1, each,
          new KeyTransformer(), new ValueFactory(), null, null));
      assertEquals(each, putIfAbsentGetIfPresent(map1, each,
          new KeyTransformer(), new ValueFactory(), null, null));
    }, 1, executor);
    assertEquals(map1, map2);
  }

  @Test
  void concurrentClear() {
    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(100), each -> {
      for (int i = 0; i < 10; i++) {
        map.put(each + i * 1000, each);
      }
      map.clear();
    }, 1, executor);
    Verify.assertEmpty(map);
  }

  @Test
  void concurrentRemoveAndPutIfAbsent() {
    ConcurrentMutableMap<Integer, Integer> map1 = newMap();
    ParallelIterate.forEach(Interval.oneTo(100), each -> {
      assertNull(map1.put(each, each));
      map1.remove(each);
      assertNull(map1.get(each));
      assertEquals(each, getIfAbsentPut(map1, each, Functions.getIntegerPassThru()));
      map1.remove(each);
      assertNull(map1.get(each));
      assertEquals(each,
          map1.getIfAbsentPutWith(each, Functions.getIntegerPassThru(), each));
      map1.remove(each);
      assertNull(map1.get(each));
      for (int i = 0; i < 10; i++) {
        assertNull(map1.putIfAbsent(each + i * 1000, each));
      }
      for (int i = 0; i < 10; i++) {
        assertEquals(each, map1.putIfAbsent(each + i * 1000, each));
      }
      for (int i = 0; i < 10; i++) {
        assertEquals(each, map1.remove(each + i * 1000));
      }
    }, 1, executor);
  }

  @Test
  void emptyToString() {
    ConcurrentMutableMap<?, ?> empty = newMap();
    assertEquals("{}", empty.toString());
  }

  private static class KeyTransformer implements Function2<Integer, Integer, Integer> {
    private static final long serialVersionUID = 1L;

    @Override
    public Integer value(Integer key, Integer value) {
      return key;
    }
  }

  private static class ValueFactory implements Function3<Object, Object, Integer, Integer> {
    private static final long serialVersionUID = 1L;

    @Override
    public Integer value(Object argument1, Object argument2, Integer key) {
      return key;
    }
  }
}
