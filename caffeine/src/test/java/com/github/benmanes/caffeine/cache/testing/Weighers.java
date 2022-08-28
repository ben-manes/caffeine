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
package com.github.benmanes.caffeine.cache.testing;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.util.concurrent.ThreadLocalRandom;

import com.github.benmanes.caffeine.cache.Weigher;

/**
 * Some common weigher implementations for tests.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Weighers {

  private Weighers() {}

  /** A weigher that returns a constant value. */
  public static <K, V> Weigher<K, V> constant(int weight) {
    return new ConstantWeigher<>(weight);
  }

  /** A weigher that returns a random value. */
  @SuppressWarnings("unchecked")
  public static <K, V> Weigher<K, V> random() {
    return (Weigher<K, V>) RandomWeigher.INSTANCE;
  }

  static final class ConstantWeigher<K, V> implements Weigher<K, V>, Serializable {
    private static final long serialVersionUID = 1L;

    private final int weight;

    ConstantWeigher(int weight) {
      this.weight = weight;
    }
    @Override public int weigh(Object key, Object value) {
      requireNonNull(key);
      requireNonNull(value);
      return weight;
    }
  }

  enum RandomWeigher implements Weigher<Object, Object> {
    INSTANCE;

    @Override public int weigh(Object key, Object value) {
      requireNonNull(key);
      requireNonNull(value);
      return ThreadLocalRandom.current().nextInt(1, 10);
    }
  }
}
