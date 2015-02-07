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

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.testing.AbstractPackageSanityTests;

/**
 * Basic sanity tests for the entire package.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class PackageSanityTests extends AbstractPackageSanityTests {

  public PackageSanityTests() {
    publicApiOnly();
    setDefault(CacheLoader.class, key -> key);
    setDefault(Caffeine.class, Caffeine.newBuilder());
    setDefault(com.google.common.cache.CacheLoader.class,
        new com.google.common.cache.CacheLoader<Object, Object>() {
          @Override public Object load(Object key) {
            return key;
          }
        });
    ignoreClasses(clazz ->
        clazz.getSimpleName().contains("Test") ||
        clazz.getSimpleName().contains("Stresser") ||
        clazz.getSimpleName().contains("Generator") ||
        clazz.getSimpleName().contains("Benchmark"));
  }
}
