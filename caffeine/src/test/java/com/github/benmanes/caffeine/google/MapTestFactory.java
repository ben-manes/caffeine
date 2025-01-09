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

import static java.util.Objects.requireNonNull;

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
    return context.isSync()
        ? Stream.of(synchronousTestSuite(context))
        : Stream.of(synchronousTestSuite(context), asynchronousTestSuite(context));
  }

  private static TestSuite synchronousTestSuite(CacheContext template) {
    return newTestSuite("Cache[" + template + "]", new SyncTestMapGenerator(template));
  }

  private static TestSuite asynchronousTestSuite(CacheContext template) {
    return newTestSuite("AsyncCache[" + template + "]", new AsyncTestMapGenerator(template));
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

  private static final class SyncTestMapGenerator extends TestStringMapGenerator {
    final CacheContext template;

    SyncTestMapGenerator(CacheContext template) {
      this.template = requireNonNull(template);
    }

    @Override
    protected Map<String, String> create(Map.Entry<String, String>[] entries) {
      var context = new CacheContext(template);
      CacheGenerator.initialize(context);

      @SuppressWarnings("unchecked")
      var cache = (Cache<String, String>) context.cache();
      for (var entry : entries) {
        cache.put(entry.getKey(), entry.getValue());
      }
      return cache.asMap();
    }
  }

  private static final class AsyncTestMapGenerator
      implements TestMapGenerator<String, CompletableFuture<String>> {
    static final CompletableFuture<String> JAN = CompletableFuture.completedFuture("January");
    static final CompletableFuture<String> FEB = CompletableFuture.completedFuture("February");
    static final CompletableFuture<String> MARCH = CompletableFuture.completedFuture("March");
    static final CompletableFuture<String> APRIL = CompletableFuture.completedFuture("April");
    static final CompletableFuture<String> MAY = CompletableFuture.completedFuture("May");

    final CacheContext template;

    AsyncTestMapGenerator(CacheContext template) {
      this.template = requireNonNull(template);
    }

    @Override
    public SampleElements<Map.Entry<String, CompletableFuture<String>>> samples() {
      return new SampleElements<>(Map.entry("one", JAN), Map.entry("two", FEB),
          Map.entry("three", MARCH), Map.entry("four", APRIL), Map.entry("five", MAY));
    }

    @Override
    public Map<String, CompletableFuture<String>> create(Object... entries) {
      var context = new CacheContext(template);
      CacheGenerator.initialize(context);

      @SuppressWarnings("unchecked")
      var cache = (AsyncCache<String, String>) context.asyncCache();
      for (Object item : entries) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        var entry = (Map.Entry<String, CompletableFuture<String>>) item;
        cache.put(entry.getKey(), entry.getValue());
      }
      return cache.asMap();
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map.Entry<String, CompletableFuture<String>>[] createArray(int length) {
      return new Map.Entry[length];
    }

    @Override
    public String[] createKeyArray(int length) {
      return new String[length];
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public CompletableFuture<String>[] createValueArray(int length) {
      return new CompletableFuture[length];
    }

    @Override
    public List<Map.Entry<String, CompletableFuture<String>>> order(
        List<Map.Entry<String, CompletableFuture<String>>> insertionOrder) {
      return insertionOrder;
    }
  }
}
