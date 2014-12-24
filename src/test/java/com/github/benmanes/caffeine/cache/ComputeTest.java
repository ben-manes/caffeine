/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import org.testng.annotations.Test;

import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class ComputeTest {

  // disabled due to live lock
  @Test(enabled = false, expectedExceptions = IllegalStateException.class)
  public void recursive_chm() {
    ConcurrentMap<Integer, Boolean> map = new ConcurrentHashMap<>();
    map.computeIfAbsent(1, new RecursiveFunction(map));
  }

  @Test(expectedExceptions = StackOverflowError.class)
  public void recursive_cslm() {
    ConcurrentMap<Integer, Boolean> map = new ConcurrentSkipListMap<>();
    map.computeIfAbsent(1, new RecursiveFunction(map));
  }

  // disabled due to live lock
  @Test(enabled = false, expectedExceptions = IllegalStateException.class)
  public void recursive_caffeine() throws ExecutionException {
    Cache<Integer, Boolean> cache = Caffeine.newBuilder().build();
    cache.get(1, new RecursiveFunction(cache.asMap()));
  }

  @Test(expectedExceptions = UncheckedExecutionException.class)
  public void recursive_guava() throws ExecutionException {
    com.google.common.cache.Cache<Integer, Boolean> cache = CacheBuilder.newBuilder().build();
    cache.get(1, new GuavaRecursiveCallable(cache));
  }

  static class RecursiveFunction implements Function<Integer, Boolean> {
    ConcurrentMap<Integer, Boolean> map;

    RecursiveFunction(ConcurrentMap<Integer, Boolean> map) {
      this.map = map;
    }

    @Override
    public Boolean apply(Integer key) {
      return map.computeIfAbsent(key, this);
    }
  }

  static class GuavaRecursiveCallable implements Callable<Boolean> {
    com.google.common.cache.Cache<Integer, Boolean> cache;

    GuavaRecursiveCallable(com.google.common.cache.Cache<Integer, Boolean> cache) {
      this.cache = cache;
    }

    @Override
    public Boolean call() throws ExecutionException {
      return cache.get(1, this);
    }
  }
}
