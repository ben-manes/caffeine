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

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import com.google.common.collect.testing.SetTestSuiteBuilder;
import com.google.common.collect.testing.TestStringSetGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;

import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class InternerMapTests extends TestCase {

  @SuppressWarnings("PMD.JUnit4SuitesShouldUseSuiteAnnotation")
  public static TestSuite suite() {
    return SetTestSuiteBuilder
        .using(new TestStringSetGenerator() {
          @Override protected Set<String> create(String[] elements) {
            var set = Collections.newSetFromMap(new WeakInterner<String>().cache);
            set.addAll(Arrays.asList(elements));
            return set;
          }
        })
        .named("Interner")
        .withFeatures(
            CollectionSize.ANY,
            CollectionFeature.GENERAL_PURPOSE)
        .createTestSuite();
  }
}
