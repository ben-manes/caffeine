/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import com.google.common.cache.Cache;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Caffeine<K, V> {

  public static Caffeine<Object, Object> newBuilder() {
    return new Caffeine<Object, Object>();
  }

  public <K1 extends K, V1 extends V> Cache<K1, V1> build() {
    return null;
  }
}
