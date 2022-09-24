/*
 * Copyright 2022 Ben Manes. All Rights Reserved.
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

import com.github.benmanes.caffeine.cache.sketch.CountMinSketch;
import com.github.benmanes.caffeine.cache.sketch.TinyLfuSketch;

/**
 * A factory for creating a {@link TinyLfuSketch} implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum SketchType {
  Flat {
    @Override public <E> TinyLfuSketch<E> create(long estimatedSize) {
      return new CountMinSketch<>(estimatedSize);
    }
  },
  Block {
    @Override public <E> TinyLfuSketch<E> create(long estimatedSize) {
      var frequencySketch = new FrequencySketch<E>();
      frequencySketch.ensureCapacity(estimatedSize);
      return new TinyLfuSketch<>() {
        @Override public int frequency(E e) {
          return frequencySketch.frequency(e);
        }
        @Override public void increment(E e) {
          frequencySketch.increment(e);
        }
        @Override public void reset() {
          frequencySketch.reset();
        }
      };
    }
  };

  /** Creates the sketch supporting the estimated maximum size. */
  public abstract <E> TinyLfuSketch<E> create(long estimatedSize);
}
