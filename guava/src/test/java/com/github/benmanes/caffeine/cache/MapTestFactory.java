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

import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.testing.MapTestSuiteBuilder;
import com.google.common.collect.testing.TestStringMapGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;
import com.google.common.collect.testing.features.MapFeature;

import junit.framework.Test;

/**
 * A JUnit test suite factory for the map tests from Guava's testlib.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class MapTestFactory {

  private MapTestFactory() {}

  /**
   * Returns a test suite.
   *
   * @param name the name of the cache type under test
   * @param builder the preconfigured cache builder
   * @param async if the cache is asynchronous
   * @return a suite of tests
   */
  protected static Test suite(String name, Caffeine<Object, Object> builder, boolean async)
      throws NoSuchMethodException, SecurityException {
    return MapTestSuiteBuilder
        .using(new TestStringMapGenerator() {
          @Override protected Map<String, String> create(Entry<String, String>[] entries) {
            Map<String, String> map = async
                ? builder.<String, String>buildAsync(key -> null).synchronous().asMap()
                : builder.<String, String>build().asMap();
            for (Entry<String, String> entry : entries) {
              map.put(entry.getKey(), entry.getValue());
            }
            return map;
          }
        })
        .named(name)
        .withFeatures(
            MapFeature.GENERAL_PURPOSE,
            CollectionFeature.SUPPORTS_ITERATOR_REMOVE,
            CollectionSize.ANY)
        .createTestSuite();
  }
}
