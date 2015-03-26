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
package com.github.benmanes.caffeine.jcache.configuration;

import java.util.Map;

import javax.cache.integration.CacheLoader;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TestCacheLoader implements CacheLoader<Integer, Integer> {
  @Override public Integer load(Integer key) {
    return null;
  }
  @Override public Map<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
    return null;
  }
}
