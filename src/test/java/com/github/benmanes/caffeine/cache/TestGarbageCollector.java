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
package com.github.benmanes.caffeine.cache;

import com.github.benmanes.caffeine.cache.BoundedLocalCache.LocalManualCache;

/**
 * TestNG holds a reference for every parameterized object to show the toString() representation
 * in tests, even after having reported this information. This unfortunately results in a lot of
 * wasted memory despite other attempts to be GC friendly.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class TestGarbageCollector {

  private TestGarbageCollector() {}

  public static <K, V> void discard(Cache<K, V> cache) {
    if (cache instanceof BoundedLocalCache.LocalManualCache<?, ?>) {
      LocalManualCache<?, ?> manual = (LocalManualCache<?, ?>) cache;
      if (manual.cache.evicts() || manual.cache.expiresAfterAccess()) {
        for (int i = 0; i < manual.cache.readBuffers.length; i++) {
          for (int j = 0; j < manual.cache.readBuffers[i].length; j++) {
            manual.cache.readBuffers[i][j] = null;
          }
          manual.cache.readBuffers[i] = null;
          manual.cache.readBufferWriteCount[i] = null;
          manual.cache.readBufferDrainAtWriteCount[i] = null;
        }
      }
      manual.cache = null;
    }
  }
}
