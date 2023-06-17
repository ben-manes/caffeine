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

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.collections.api.bag.MutableBag;
import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.block.function.Function3;
import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.bag.mutable.HashBag;
import org.eclipse.collections.impl.block.factory.Functions;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.list.Interval;
import org.eclipse.collections.impl.parallel.ParallelIterate;
import org.eclipse.collections.impl.test.Verify;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * JUnit test for {@link ConcurrentHashMap}.
 *
 * Ported from Eclipse Collections 11.0.
 */
@SuppressWarnings("CanIgnoreReturnValueSuggester")
public abstract class ConcurrentHashMapAcceptanceTest {
  private ExecutorService executor;

  @Before
  public void setUp() {
    executor = Executors.newFixedThreadPool(20);
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  public abstract <K, V> ConcurrentMutableMap<K, V> newMap();

  public <K, V> ConcurrentMutableMap<K, V> newMap(Map<K, V> other) {
    ConcurrentMutableMap<K, V> map = newMap();
    map.putAll(other);
    return map;
  }

  public <K, V> V getIfAbsentPut(ConcurrentMutableMap<K, V> map,
      K key, Function<? super K, ? extends V> factory) {
    return map.computeIfAbsent(key, factory);
  }

  public <K, V, P1, P2> V putIfAbsentGetIfPresent(ConcurrentMutableMap<K, V> map, K key,
      Function2<? super K, ? super V, ? extends K> keyTransformer,
      Function3<P1, P2, ? super K, ? extends V> factory, P1 param1, P2 param2) {
    boolean[] added = { false };
    V newValue = map.computeIfAbsent(keyTransformer.apply(key, null), k -> {
      added[0] = true;
      return factory.value(param1, param2, key);
    });
    return added[0] ? null : newValue;
  }

  @Test
  public void parallelGroupByIntoConcurrentHashMap() {
    MutableMap<Integer, MutableBag<Integer>> actual = newMap();
    ParallelIterate.forEach(Interval.oneTo(1000000), each -> actual
        .getIfAbsentPut(each % 100000, () -> HashBag.<Integer>newBag().asSynchronized()).add(each),
        10, executor);
    Verify.assertEqualsAndHashCode(
        Interval.oneTo(1000000).groupBy(each -> each % 100000).toMap(HashBag::new), actual);
  }

  @Test
  public void concurrentPutGetPutAllRemoveContainsKeyContainsValueGetIfAbsentPutTest() {
    ConcurrentMutableMap<Integer, Integer> map1 = newMap();
    ConcurrentMutableMap<Integer, Integer> map2 = newMap();
    ParallelIterate.forEach(Interval.oneTo(1000), each -> {
      map1.put(each, each);
      Assert.assertEquals(each, map1.get(each));
      map2.putAll(Maps.mutable.of(each, each));
      map1.remove(each);
      map1.putAll(Maps.mutable.of(each, each));
      Assert.assertEquals(each, map2.get(each));
      map2.remove(each);
      Assert.assertNull(map2.get(each));
      Assert.assertFalse(map2.containsValue(each));
      Assert.assertFalse(map2.containsKey(each));
      Assert.assertEquals(each, getIfAbsentPut(map2, each, Functions.getIntegerPassThru()));
      Assert.assertTrue(map2.containsValue(each));
      Assert.assertTrue(map2.containsKey(each));
      Assert.assertEquals(each, getIfAbsentPut(map2, each, Functions.getIntegerPassThru()));
      map2.remove(each);
      Assert.assertEquals(each,
          map2.getIfAbsentPutWith(each, Functions.getIntegerPassThru(), each));
      Assert.assertEquals(each,
          map2.getIfAbsentPutWith(each, Functions.getIntegerPassThru(), each));
      Assert.assertEquals(each, getIfAbsentPut(map2, each, Functions.getIntegerPassThru()));
    }, 10, executor);
    Verify.assertEqualsAndHashCode(map1, map2);
  }

  @Test
  public void concurrentPutIfAbsentGetIfPresentPutTest() {
    ConcurrentMutableMap<Integer, Integer> map1 = newMap();
    ConcurrentMutableMap<Integer, Integer> map2 = newMap();
    ParallelIterate.forEach(Interval.oneTo(1000), each -> {
      map1.put(each, each);
      map1.put(each, each);
      Assert.assertEquals(each, map1.get(each));
      map2.putAll(Maps.mutable.of(each, each));
      map2.putAll(Maps.mutable.of(each, each));
      map1.remove(each);
      Assert.assertNull(putIfAbsentGetIfPresent(
          map1, each, new KeyTransformer(), new ValueFactory(), null, null));
      Assert.assertEquals(each, putIfAbsentGetIfPresent(
          map1, each, new KeyTransformer(), new ValueFactory(), null, null));
    }, 10, executor);
    Assert.assertEquals(map1, map2);
  }

  @Test
  public void concurrentClear() {
    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(1000), each -> {
      for (int i = 0; i < each; i++) {
        map.put(each + i * 1000, each);
      }
      map.clear();
      for (int i = 0; i < 100; i++) {
        map.put(each + i * 1000, each);
      }
      map.clear();
    }, 10, executor);
    Verify.assertEmpty(map);
  }

  @Test
  public void concurrentRemoveAndPutIfAbsent() {
    ConcurrentMutableMap<Integer, Integer> map1 = newMap();
    ParallelIterate.forEach(Interval.oneTo(1000), each -> {
      Assert.assertNull(map1.put(each, each));
      map1.remove(each);
      Assert.assertNull(map1.get(each));
      Assert.assertEquals(each, getIfAbsentPut(map1, each, Functions.getIntegerPassThru()));
      map1.remove(each);
      Assert.assertNull(map1.get(each));
      Assert.assertEquals(each,
          map1.getIfAbsentPutWith(each, Functions.getIntegerPassThru(), each));
      map1.remove(each);
      Assert.assertNull(map1.get(each));
      for (int i = 0; i < each; i++) {
        Assert.assertNull(map1.putIfAbsent(each + i * 1000, each));
      }
      for (int i = 0; i < each; i++) {
        Assert.assertEquals(each, map1.putIfAbsent(each + i * 1000, each));
      }
      for (int i = 0; i < each; i++) {
        Assert.assertEquals(each, map1.remove(each + i * 1000));
      }
    }, 10, executor);
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

  @Test
  public void size() {
    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(10_000), each -> map.put(each, each));
    Assert.assertEquals(10_000, map.size());
    Assert.assertEquals(10_000, map.keySet().size());
    Assert.assertEquals(10_000, map.values().size());
    Assert.assertEquals(10_000, map.entrySet().size());
  }

  @Test
  public void size_entrySet() {
    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(10_000), each -> map.put(each, each));
    Assert.assertEquals(10_000, map.entrySet().size());
  }

  @Test
  public void size_keySet() {
    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(10_000), each -> map.put(each, each));
    Assert.assertEquals(10_000, map.keySet().size());
  }

  @Test
  public void size_values() {
    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(10_000), each -> map.put(each, each));
    Assert.assertEquals(10_000, map.values().size());
  }
}
