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

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.eclipse.collections.impl.bag.mutable.HashBag;
import org.eclipse.collections.impl.list.Interval;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.parallel.ParallelIterate;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Ported from Eclipse Collections 11.0.
 */
public abstract class ConcurrentHashMapTestCase extends MutableMapTestCase {
  protected ExecutorService executor;

  @Before
  public void setUp() {
    executor = Executors.newFixedThreadPool(20);
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  @Override
  protected abstract <K, V> ConcurrentMutableMap<K, V> newMap();

  @Override
  @Test
  public void updateValue() {
    super.updateValue();

    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(100),
        each -> map.updateValue(each % 10, () -> 0, integer -> integer + 1), 1, executor);
    Assert.assertEquals(Interval.zeroTo(9).toSet(), map.keySet());
    Assert.assertEquals(FastList.newList(Collections.nCopies(10, 10)),
        FastList.newList(map.values()));
  }

  @Override
  @Test
  public void updateValue_collisions() {
    super.updateValue_collisions();

    ConcurrentMutableMap<Integer, Integer> map = newMap();
    MutableList<Integer> list = Interval.oneTo(100).toList().shuffleThis();
    ParallelIterate.forEach(list,
        each -> map.updateValue(each % 50, () -> 0, integer -> integer + 1), 1, executor);
    Assert.assertEquals(Interval.zeroTo(49).toSet(), map.keySet());
    Assert.assertEquals(HashBag.newBag(map.values()).toStringOfItemToCount(),
        FastList.newList(Collections.nCopies(50, 2)), FastList.newList(map.values()));
  }

  @Override
  @Test
  public void updateValueWith() {
    super.updateValueWith();

    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(100),
        each -> map.updateValueWith(each % 10, () -> 0, (integer, parameter) -> {
          Assert.assertEquals("test", parameter);
          return integer + 1;
        }, "test"), 1, executor);
    Assert.assertEquals(Interval.zeroTo(9).toSet(), map.keySet());
    Assert.assertEquals(FastList.newList(Collections.nCopies(10, 10)),
        FastList.newList(map.values()));
  }

  @Override
  @Test
  public void updateValueWith_collisions() {
    super.updateValueWith_collisions();

    ConcurrentMutableMap<Integer, Integer> map = newMap();
    MutableList<Integer> list = Interval.oneTo(200).toList().shuffleThis();
    ParallelIterate.forEach(list,
        each -> map.updateValueWith(each % 100, () -> 0, (integer, parameter) -> {
          Assert.assertEquals("test", parameter);
          return integer + 1;
        }, "test"), 1, executor);
    Assert.assertEquals(Interval.zeroTo(99).toSet(), map.keySet());
    Assert.assertEquals(HashBag.newBag(map.values()).toStringOfItemToCount(),
        FastList.newList(Collections.nCopies(100, 2)), FastList.newList(map.values()));
  }
}
