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
package com.github.benmanes.caffeine.google;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheGenerator;
import com.google.common.collect.testing.ConcurrentMapTestSuiteBuilder;
import com.google.common.collect.testing.SampleElements;
import com.google.common.collect.testing.TestMapGenerator;
import com.google.common.collect.testing.TestStringMapGenerator;
import com.google.common.collect.testing.features.CollectionFeature;
import com.google.common.collect.testing.features.CollectionSize;
import com.google.common.collect.testing.features.MapFeature;

import junit.framework.TestSuite;

/**
 * A JUnit test suite factory for the map tests from Guava's testlib.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class MapTestFactory {

  private MapTestFactory() {}

  /** Returns a test suite based on the supplied configuration. */
  public static Stream<TestSuite> makeTests(CacheContext context) {
    var tests = new ArrayList<TestSuite>(2);
    CacheGenerator.initialize(context);

    @SuppressWarnings("unchecked")
    var cache = (Cache<String, String>) context.cache();
    tests.add(newTestSuite("Cache[" + context + "]", synchronousGenerator(cache)));

    if (context.isAsync()) {
      @SuppressWarnings("unchecked")
      var asyncCache = (AsyncCache<String, String>) context.asyncCache();
      tests.add(newTestSuite("AsyncCache[" + context + "]", asynchronousGenerator(asyncCache)));
    }

    return tests.stream();
  }

  /** Returns a test suite. */
  private static TestSuite newTestSuite(String name, TestMapGenerator<?, ?> generator) {
    return ConcurrentMapTestSuiteBuilder
        .using(generator)
        .named(name)
        .withFeatures(
            CollectionSize.ANY,
            MapFeature.GENERAL_PURPOSE,
            MapFeature.ALLOWS_NULL_ENTRY_QUERIES,
            CollectionFeature.SUPPORTS_ITERATOR_REMOVE)
        .createTestSuite();
  }

  /** Returns a map generator for synchronous values. */
  private static TestStringMapGenerator synchronousGenerator(Cache<String, String> cache) {
    return new TestStringMapGenerator() {
      @Override protected Map<String, String> create(Map.Entry<String, String>[] entries) {
        cache.invalidateAll();
        for (var entry : entries) {
          cache.put(entry.getKey(), entry.getValue());
        }
        return cache.asMap();
      }
    };
  }

  /** Returns a map generator for asynchronous values. */
  private static TestAsyncMapGenerator asynchronousGenerator(AsyncCache<String, String> cache) {
    return new TestAsyncMapGenerator() {
      @Override protected Map<String, CompletableFuture<String>> create(
          List<Map.Entry<String, CompletableFuture<String>>> entries) {
        cache.synchronous().invalidateAll();
        for (var entry : entries) {
          cache.put(entry.getKey(), entry.getValue());
        }
        return cache.asMap();
      }
    };
  }

  private abstract static class TestAsyncMapGenerator
      implements TestMapGenerator<String, CompletableFuture<String>> {
    static final CompletableFuture<String> JAN = CompletableFuture.completedFuture("January");
    static final CompletableFuture<String> FEB = CompletableFuture.completedFuture("February");
    static final CompletableFuture<String> MARCH = CompletableFuture.completedFuture("March");
    static final CompletableFuture<String> APRIL = CompletableFuture.completedFuture("April");
    static final CompletableFuture<String> MAY = CompletableFuture.completedFuture("May");

    @Override
    public SampleElements<Map.Entry<String, CompletableFuture<String>>> samples() {
      return new SampleElements<>(Map.entry("one", JAN), Map.entry("two", FEB),
          Map.entry("three", MARCH), Map.entry("four", APRIL), Map.entry("five", MAY));
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, CompletableFuture<String>> create(Object... entries) {
      var data = new ArrayList<Map.Entry<String, CompletableFuture<String>>>(entries.length);
      for (Object entry : entries) {
        data.add((Map.Entry<String, CompletableFuture<String>>) entry);
      }
      return create(data);
    }

    protected abstract Map<String, CompletableFuture<String>> create(
        List<Map.Entry<String, CompletableFuture<String>>> entries);

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public final Map.Entry<String, CompletableFuture<String>>[] createArray(int length) {
      return new Map.Entry[length];
    }

    @Override
    public final String[] createKeyArray(int length) {
      return new String[length];
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override public final CompletableFuture<String>[] createValueArray(int length) {
      return new CompletableFuture[length];
    }

    @Override
    public Iterable<Map.Entry<String, CompletableFuture<String>>> order(
        List<Map.Entry<String, CompletableFuture<String>>> insertionOrder) {
      return insertionOrder;
    }
  }
}
