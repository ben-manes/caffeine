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

import java.util.Collection;

import javax.cache.Cache;
import javax.cache.integration.CacheWriter;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class TestCacheWriter implements CacheWriter<Integer, Integer> {
  @Override public void write(Cache.Entry<? extends Integer, ? extends Integer> entry) {}
  @Override public void writeAll(
      Collection<Cache.Entry<? extends Integer, ? extends Integer>> entries) {}
  @Override public void delete(Object key) {}
  @Override public void deleteAll(Collection<?> keys) {}
}
