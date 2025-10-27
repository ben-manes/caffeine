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
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.ConcurrentMutableMap;
import org.eclipse.collections.impl.bag.mutable.HashBag;
import org.eclipse.collections.impl.list.Interval;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.parallel.ParallelIterate;
import org.junit.jupiter.api.Test;

/**
 * Ported from Eclipse Collections 11.0.
 */
abstract class ConcurrentHashMapTestCase extends MutableMapTestCase {

  @Override
  @Test
  void updateValue() {
    super.updateValue();

    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(100),
        each -> map.updateValue(each % 10, () -> 0, integer -> integer + 1), 1, executor);
    assertEquals(Interval.zeroTo(9).toSet(), map.keySet());
    assertEquals(FastList.newList(Collections.nCopies(10, 10)),
        FastList.newList(map.values()));
  }

  @Override
  @Test
  void updateValue_collisions() {
    super.updateValue_collisions();

    ConcurrentMutableMap<Integer, Integer> map = newMap();
    MutableList<Integer> list = Interval.oneTo(100).toList().shuffleThis();
    ParallelIterate.forEach(list,
        each -> map.updateValue(each % 50, () -> 0, integer -> integer + 1), 1, executor);
    assertEquals(Interval.zeroTo(49).toSet(), map.keySet());
    assertEquals(FastList.newList(Collections.nCopies(50, 2)), FastList.newList(map.values()),
        HashBag.newBag(map.values()).toStringOfItemToCount());
  }

  @Override
  @Test
  void updateValueWith() {
    super.updateValueWith();

    ConcurrentMutableMap<Integer, Integer> map = newMap();
    ParallelIterate.forEach(Interval.oneTo(100),
        each -> map.updateValueWith(each % 10, () -> 0, (integer, parameter) -> {
          assertEquals("test", parameter);
          return integer + 1;
        }, "test"), 1, executor);
    assertEquals(Interval.zeroTo(9).toSet(), map.keySet());
    assertEquals(FastList.newList(Collections.nCopies(10, 10)),
        FastList.newList(map.values()));
  }

  @Override
  @Test
  void updateValueWith_collisions() {
    super.updateValueWith_collisions();

    ConcurrentMutableMap<Integer, Integer> map = newMap();
    MutableList<Integer> list = Interval.oneTo(200).toList().shuffleThis();
    ParallelIterate.forEach(list,
        each -> map.updateValueWith(each % 100, () -> 0, (integer, parameter) -> {
          assertEquals("test", parameter);
          return integer + 1;
        }, "test"), 1, executor);
    assertEquals(Interval.zeroTo(99).toSet(), map.keySet());
    assertEquals(FastList.newList(Collections.nCopies(100, 2)), FastList.newList(map.values()),
        HashBag.newBag(map.values()).toStringOfItemToCount());
  }
}
