/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.testing;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Garbage collector tricks.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class GarbageCollector {

  private GarbageCollector() {}

  public static void awaitSoftRefGc() {
    byte[] garbage = new byte[1024];
    SoftReference<Object> flag = new SoftReference<>(new Object());
    List<Object> softRefs = new ArrayList<>();
    while (flag.get() != null) {
      int free = Math.abs((int) Runtime.getRuntime().freeMemory());
      int nextLength = Math.max(garbage.length, garbage.length << 2);
      garbage = new byte[Math.min(free >> 2, nextLength)];
      softRefs.add(new SoftReference<>(garbage));
    }
  }
}
