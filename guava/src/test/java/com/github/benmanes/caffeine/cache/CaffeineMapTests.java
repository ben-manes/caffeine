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

import static com.github.benmanes.caffeine.cache.MapTestFactory.asynchronousGenerator;
import static com.github.benmanes.caffeine.cache.MapTestFactory.synchronousGenerator;

import com.github.benmanes.caffeine.guava.CaffeinatedGuava;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Guava testlib map tests for the {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeineMapTests extends TestCase {

  public static Test suite() throws Exception {
    TestSuite suite = new TestSuite();
    addGuavaViewTests(suite);
    addUnboundedTests(suite);
    addBoundedTests(suite);
    return suite;
  }

  private static void addUnboundedTests(TestSuite suite) throws Exception {
    suite.addTest(MapTestFactory.suite("UnboundedCache", synchronousGenerator(() -> {
      Cache<String, String> cache = Caffeine.newBuilder().build();
      return cache.asMap();
    })));
    suite.addTest(MapTestFactory.suite("UnboundedAsyncCache", synchronousGenerator(() -> {
      AsyncCache<String, String> cache = Caffeine.newBuilder().buildAsync();
      return cache.synchronous().asMap();
    })));
    suite.addTest(MapTestFactory.suite("UnboundedAsyncCache", asynchronousGenerator(() -> {
      AsyncCache<String, String> cache = Caffeine.newBuilder().buildAsync();
      return cache.asMap();
    })));
  }

  private static void addBoundedTests(TestSuite suite) throws Exception {
    suite.addTest(MapTestFactory.suite("BoundedCache", synchronousGenerator(() -> {
      Cache<String, String> cache = Caffeine.newBuilder().maximumSize(Long.MAX_VALUE).build();
      return cache.asMap();
    })));
    suite.addTest(MapTestFactory.suite("BoundedAsyncCache", synchronousGenerator(() -> {
      AsyncCache<String, String> cache = Caffeine.newBuilder()
          .maximumSize(Long.MAX_VALUE)
          .buildAsync();
      return cache.synchronous().asMap();
    })));
    suite.addTest(MapTestFactory.suite("BoundedAsyncCache", asynchronousGenerator(() -> {
      AsyncCache<String, String> cache = Caffeine.newBuilder()
          .maximumSize(Long.MAX_VALUE)
          .buildAsync();
      return cache.asMap();
    })));
  }

  private static void addGuavaViewTests(TestSuite suite) throws Exception {
    suite.addTest(MapTestFactory.suite("GuavaView", synchronousGenerator(() -> {
      com.google.common.cache.Cache<String, String> cache = CaffeinatedGuava.build(
          Caffeine.newBuilder().maximumSize(Long.MAX_VALUE));
      return cache.asMap();
    })));
    suite.addTest(MapTestFactory.suite("GuavaLoadingView", synchronousGenerator(() -> {
      com.google.common.cache.Cache<String, String> cache = CaffeinatedGuava.build(
          Caffeine.newBuilder().maximumSize(Long.MAX_VALUE), key -> null);
      return cache.asMap();
    })));
  }
}
