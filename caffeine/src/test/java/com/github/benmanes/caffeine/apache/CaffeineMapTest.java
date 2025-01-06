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

import java.time.Duration;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.collections4.map.AbstractMapTest;
import org.junit.jupiter.api.Nested;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Apache Commons Collections' map tests for the {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class CaffeineMapTest {

  private CaffeineMapTest() {}

  @Nested
  static final class BoundedSyncMapTest extends AbstractConcurrentMapTest {
    @Override public ConcurrentMap<Object, Object> makeObject() {
      return Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofNanos(Long.MAX_VALUE))
          .maximumSize(Long.MAX_VALUE)
          .build().asMap();
    }
  }

  @Nested
  static final class BoundedAsyncMapTest extends AbstractConcurrentMapTest {
    @Override public ConcurrentMap<Object, Object> makeObject() {
      return Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofNanos(Long.MAX_VALUE))
          .maximumSize(Long.MAX_VALUE)
          .buildAsync().synchronous().asMap();
    }
  }

  @Nested
  static final class UnboundedSyncMapTest extends AbstractConcurrentMapTest {
    @Override public ConcurrentMap<Object, Object> makeObject() {
      return Caffeine.newBuilder().build().asMap();
    }
  }

  @Nested
  static final class UnboundedAsyncMapTest extends AbstractConcurrentMapTest {
    @Override public ConcurrentMap<Object, Object> makeObject() {
      return Caffeine.newBuilder().buildAsync().synchronous().asMap();
    }
  }

  abstract static class AbstractConcurrentMapTest
      extends AbstractMapTest<ConcurrentMap<Object, Object>, Object, Object> {

    @Override public boolean isAllowNullKey() {
      return false;
    }
    @Override public boolean isAllowNullValueGet() {
      return false;
    }
    @Override public boolean isAllowNullValuePut() {
      return false;
    }
    @Override public boolean isTestSerialization() {
      return false;
    }
  }
}
