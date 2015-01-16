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

import java.lang.reflect.Constructor;

import junit.framework.TestCase;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.google.common.cache.TestingCacheLoaders;
import com.google.common.testing.SerializableTester;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CaffeinatedGuavaTest extends TestCase {

  public void testSerializable() {
    SerializableTester.reserialize(CaffeinatedGuava.build(Caffeine.newBuilder()));
    SerializableTester.reserialize(CaffeinatedGuava.build(Caffeine.newBuilder(), Loader.IDENTITY));
    SerializableTester.reserialize(CaffeinatedGuava.build(
        Caffeine.newBuilder(), TestingCacheLoaders.identityLoader()));
  }

  public void testReflectivelyConstruct() throws Exception {
    Constructor<?> constructor = CaffeinatedGuava.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    constructor.newInstance();
  }

  public void testHasMethod_notFound() throws Exception {
    assertFalse(CaffeinatedGuava.hasMethod(TestingCacheLoaders.identityLoader(), "abc"));
  }
}
