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
package com.github.benmanes.caffeine.guava;

import static com.github.benmanes.caffeine.guava.MapTestFactory.generator;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.cache.Cache;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Guava testlib map tests for the {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class GuavaMapTests extends TestCase {

  @SuppressWarnings("PMD.JUnit4SuitesShouldUseSuiteAnnotation")
  public static Test suite() {
    var suite = new TestSuite();
    suite.addTest(MapTestFactory.suite("GuavaView", generator(() -> {
      Cache<String, String> cache = CaffeinatedGuava.build(
          Caffeine.newBuilder().maximumSize(Long.MAX_VALUE));
      return cache.asMap();
    })));
    suite.addTest(MapTestFactory.suite("GuavaLoadingView", generator(() -> {
      Cache<String, String> cache = CaffeinatedGuava.build(
          Caffeine.newBuilder().maximumSize(Long.MAX_VALUE),
          key -> { throw new AssertionError(); });
      return cache.asMap();
    })));
    return suite;
  }
}
