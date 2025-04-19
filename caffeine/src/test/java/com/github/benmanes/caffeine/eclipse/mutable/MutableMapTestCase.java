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

import static org.junit.jupiter.api.Assertions.assertNotSame;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.MutableMap;
import org.eclipse.collections.impl.block.factory.Functions;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.test.Verify;
import org.junit.jupiter.api.Test;

/**
 * Abstract JUnit TestCase for {@link MutableMap}s.
 *
 * Ported from Eclipse Collections 11.0.
 */
abstract class MutableMapTestCase extends MutableMapIterableTestCase {

  @Test
  void collectKeysAndValues() {
    MutableMap<Integer, String> map = newMapWithKeysValues(1, "1", 2, "Two");
    MutableList<Integer> toAdd = FastList.newListWith(2, 3);
    map.collectKeysAndValues(toAdd, Functions.getIntegerPassThru(), String::valueOf);
    Verify.assertSize(3, map);
    Verify.assertContainsAllKeyValues(map, 1, "1", 2, "2", 3, "3");
  }

  @Test
  void cloneable() {
    MutableMap<Integer, String> map = newMapWithKeysValues(1, "One", 2, "Two");
    MutableMap<Integer, String> clone = map.clone();
    assertNotSame(map, clone);
    Verify.assertEqualsAndHashCode(map, clone);
  }
}
