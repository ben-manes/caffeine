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
package com.github.benmanes.caffeine.eclipse;

import org.eclipse.collections.api.map.ConcurrentMutableMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.eclipse.acceptance.UnifiedMapAcceptanceTest;

/**
 * Eclipse Collections' map tests for the {@link Cache#asMap()} view.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class UnboundedUnifiedMapAcceptanceTest
    extends UnifiedMapAcceptanceTest implements CaffeineMutableMapTestCase {
  @Override public <K, V> Cache<K, V> newCache() {
    return Caffeine.newBuilder().build();
  }
  @Override public <K, V> ConcurrentMutableMap<K, V> newMap() {
    return CaffeineMutableMapTestCase.super.newMap();
  }
}
