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
package com.github.benmanes.caffeine.apache;

import java.util.Map;

import org.apache.commons.collections4.map.AbstractMapTest;

import com.github.benmanes.caffeine.cache.Cache;

/**
 * Apache Commons Collections' map tests for the {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class CaffeineMapTestCase extends AbstractMapTest<Object, Object> {

  public CaffeineMapTestCase(String testName) {
    super(testName);
  }
  @Override public boolean isAllowNullKey() {
    return false;
  }
  @Override public boolean isAllowNullValue() {
    return false;
  }
  @Override public boolean isFailFastExpected() {
    return false;
  }
  @Override public boolean isSubMapViewsSerializable() {
    return false;
  }
  @Override public Map<Object, Object> makeObject() {
    return makeCache().asMap();
  }

  /** Returns a new, empty cache instance. */
  public abstract Cache<Object, Object> makeCache();
}
