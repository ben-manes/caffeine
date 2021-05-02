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

import com.google.common.testing.AbstractPackageSanityTests;

/**
 * Basic sanity tests for the entire package.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class PackageSanityTests extends AbstractPackageSanityTests {

  public PackageSanityTests() {
    publicApiOnly();
    setDefault(CacheLoader.class, key -> key);
    setDefault(Caffeine.class, Caffeine.newBuilder());
    ignoreClasses(clazz ->
        clazz == CaffeineSpec.class ||
        clazz.getSimpleName().startsWith("Is") ||
        clazz.getSimpleName().endsWith("Test") ||
        clazz.getSimpleName().contains("Stresser") ||
        clazz.getSimpleName().endsWith("Generator") ||
        clazz.getSimpleName().endsWith("Benchmark")
    );
  }
}
