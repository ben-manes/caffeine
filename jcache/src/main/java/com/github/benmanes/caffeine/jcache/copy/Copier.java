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
package com.github.benmanes.caffeine.jcache.copy;

/**
 * An object is copied when the cache is configured with <tt>storeByValue</tt> to guard against
 * mutations of the key or value.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@FunctionalInterface
public interface Copier {

  /**
   * Returns a deep copy of the object.
   *
   * @param object the object to copy
   * @param classLoader the classloader to instantiate with
   * @param <T> the type of object being copied
   * @return a copy of the object
   */
  <T> T copy(T object, ClassLoader classLoader);

  /** @return a copy strategy that performs an identity function, for use by store-by-reference */
  static Copier identity() {
    return IdentityCopier.INSTANCE;
  }
}

enum IdentityCopier implements Copier {
  INSTANCE;

  @Override
  public <T> T copy(T object, ClassLoader classLoader) {
    return object;
  }
}
