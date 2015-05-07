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

import static com.github.benmanes.caffeine.cache.Specifications.ACCESS_ORDER_DEQUE;
import static com.github.benmanes.caffeine.cache.Specifications.BUILDER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.CACHE_LOADER;
import static com.github.benmanes.caffeine.cache.Specifications.CACHE_LOADER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.REMOVAL_LISTENER;
import static com.github.benmanes.caffeine.cache.Specifications.STATS_COUNTER;
import static com.github.benmanes.caffeine.cache.Specifications.TICKER;
import static com.github.benmanes.caffeine.cache.Specifications.UNSAFE_ACCESS;
import static com.github.benmanes.caffeine.cache.Specifications.WRITE_ORDER_DEQUE;
import static com.github.benmanes.caffeine.cache.Specifications.WRITE_QUEUE;
import static com.github.benmanes.caffeine.cache.Specifications.WRITE_QUEUE_TYPE;
import static com.github.benmanes.caffeine.cache.Specifications.kRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.newFieldOffset;
import static com.github.benmanes.caffeine.cache.Specifications.offsetName;
import static com.github.benmanes.caffeine.cache.Specifications.vRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;

import java.util.Set;
import java.util.concurrent.Executor;

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * Generates a cache implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheGenerator {
  private final Modifier[] publicFinalModifiers = { Modifier.PUBLIC, Modifier.FINAL };
  private final Modifier[] protectedFinalModifiers = { Modifier.PROTECTED, Modifier.FINAL };
  private final Modifier[] privateFinalModifiers = { Modifier.PRIVATE, Modifier.FINAL };
  private final Modifier[] privateVolatileModifiers = { Modifier.PRIVATE, Modifier.VOLATILE };

  private final String className;
  private final TypeSpec.Builder cache;
  private final MethodSpec.Builder constructor;

  private final Set<Feature> parentFeatures;
  private final Set<Feature> generateFeatures;

  LocalCacheGenerator(TypeName superClass, String className, boolean isFinal,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    this.className = className;
    this.parentFeatures = parentFeatures;
    this.generateFeatures = generateFeatures;
    this.cache = TypeSpec.classBuilder(className)
        .superclass(superClass)
        .addModifiers(Modifier.STATIC);
    this.constructor = MethodSpec.constructorBuilder();
    if (isFinal) {
      cache.addModifiers(Modifier.FINAL);
    }
  }

  public TypeSpec generate() {
    cache
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar);
    constructor
        .addParameter(BUILDER_PARAM)
        .addParameter(CACHE_LOADER_PARAM)
        .addParameter(boolean.class, "async")
        .addStatement(parentFeatures.isEmpty()
            ? "super(builder, async)"
            : "super(builder, cacheLoader, async)");

    addKeyStrength();
    addValueStrength();
    addCacheLoader();
    addRemovalListener();
    addExecutor();
    addStats();
    addTicker();
    addMaximum();
    addAccessOrderDeque();
    addExpireAfterAccess();
    addExpireAfterWrite();
    addRefreshAfterWrite();
    addWriteOrderDeque();
    addWriteQueue();
    return cache.addMethod(constructor.build()).build();
  }

  private void addKeyStrength() {
    if (generateFeatures.contains(Feature.WEAK_KEYS)) {
      addStrength("collectKeys", "keyReferenceQueue", kRefQueueType);
    }
  }

  private void addValueStrength() {
    if (generateFeatures.contains(Feature.INFIRM_VALUES)) {
      addStrength("collectValues", "valueReferenceQueue", vRefQueueType);
    }
  }

  private void addRemovalListener() {
    if (!generateFeatures.contains(Feature.LISTENING)) {
      return;
    }
    cache.addField(
        FieldSpec.builder(REMOVAL_LISTENER, "removalListener", privateFinalModifiers).build());
    constructor.addStatement("this.removalListener = builder.getRemovalListener(async)");
    cache.addMethod(MethodSpec.methodBuilder("removalListener")
        .addModifiers(publicFinalModifiers)
        .addStatement("return removalListener")
        .returns(REMOVAL_LISTENER)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("hasRemovalListener")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private void addExecutor() {
    if (!generateFeatures.contains(Feature.EXECUTOR)) {
      return;
    }
    cache.addField(FieldSpec.builder(Executor.class, "executor", privateFinalModifiers).build());
    constructor.addStatement("this.executor = builder.getExecutor()");
    cache.addMethod(MethodSpec.methodBuilder("executor")
        .addModifiers(publicFinalModifiers)
        .addStatement("return executor")
        .returns(Executor.class)
        .build());
  }

  private void addCacheLoader() {
    if (!generateFeatures.contains(Feature.LOADING)) {
      return;
    }
    constructor.addStatement("this.cacheLoader = cacheLoader");
    cache.addField(FieldSpec.builder(CACHE_LOADER, "cacheLoader", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("cacheLoader")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return cacheLoader")
        .returns(CACHE_LOADER)
        .build());
  }

  private void addStats() {
    if (!generateFeatures.contains(Feature.STATS)) {
      return;
    }
    constructor.addStatement("this.statsCounter = builder.getStatsCounterSupplier().get()");
    cache.addField(FieldSpec.builder(STATS_COUNTER, "statsCounter", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("statsCounter")
        .addModifiers(publicFinalModifiers)
        .addStatement("return statsCounter")
        .returns(STATS_COUNTER)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("isRecordingStats")
        .addModifiers(publicFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  private void addTicker() {
    if (Feature.usesTicker(parentFeatures) || !Feature.usesTicker(generateFeatures)) {
      return;
    }
    constructor.addStatement("this.ticker = builder.getTicker()");
    cache.addField(FieldSpec.builder(TICKER, "ticker", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("ticker")
        .addModifiers(publicFinalModifiers)
        .addStatement("return ticker")
        .returns(TICKER)
        .build());
  }

  private void addMaximum() {
    if (Feature.usesMaximum(parentFeatures) || !Feature.usesMaximum(generateFeatures)) {
      return;
    }
    cache.addMethod(MethodSpec.methodBuilder("evicts")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());

    constructor.addStatement(
        "this.maximum = $T.min(builder.getMaximumWeight(), MAXIMUM_CAPACITY)", Math.class);
    cache.addField(FieldSpec.builder(long.class, "maximum", privateVolatileModifiers).build());
    cache.addField(newFieldOffset(className, "maximum"));
    cache.addMethod(MethodSpec.methodBuilder("maximum")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return $T.UNSAFE.getLong(this, $N)", UNSAFE_ACCESS, offsetName("maximum"))
        .returns(long.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("lazySetMaximum")
        .addModifiers(protectedFinalModifiers)
        .addStatement("$T.UNSAFE.putLong(this, $N, $N)",
            UNSAFE_ACCESS, offsetName("maximum"), "maximum")
        .addParameter(long.class, "maximum")
        .build());

    cache.addField(FieldSpec.builder(long.class, "weightedSize", privateVolatileModifiers).build());
    cache.addField(newFieldOffset(className, "weightedSize"));
    cache.addMethod(MethodSpec.methodBuilder("weightedSize")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return $T.UNSAFE.getLong(this, $N)",
            UNSAFE_ACCESS, offsetName("weightedSize"))
        .returns(long.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("lazySetWeightedSize")
        .addModifiers(protectedFinalModifiers)
        .addStatement("$T.UNSAFE.putLong(this, $N, $N)",
            UNSAFE_ACCESS, offsetName("weightedSize"), "weightedSize")
        .addParameter(long.class, "weightedSize")
        .build());
  }

  private void addExpireAfterAccess() {
    if (!generateFeatures.contains(Feature.EXPIRE_ACCESS)) {
      return;
    }
    constructor.addStatement("this.expiresAfterAccessNanos = builder.getExpiresAfterAccessNanos()");
    cache.addField(FieldSpec.builder(long.class, "expiresAfterAccessNanos",
        privateVolatileModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterAccess")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterAccessNanos")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return expiresAfterAccessNanos")
        .returns(long.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("setExpiresAfterAccessNanos")
        .addStatement("this.expiresAfterAccessNanos = expiresAfterAccessNanos")
        .addParameter(long.class, "expiresAfterAccessNanos")
        .addModifiers(protectedFinalModifiers)
        .build());
  }

  private void addExpireAfterWrite() {
    if (!generateFeatures.contains(Feature.EXPIRE_WRITE)) {
      return;
    }
    constructor.addStatement("this.expiresAfterWriteNanos = builder.getExpiresAfterWriteNanos()");
    cache.addField(FieldSpec.builder(long.class, "expiresAfterWriteNanos",
        privateVolatileModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterWrite")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterWriteNanos")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return expiresAfterWriteNanos")
        .returns(long.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("setExpiresAfterWriteNanos")
        .addStatement("this.expiresAfterWriteNanos = expiresAfterWriteNanos")
        .addParameter(long.class, "expiresAfterWriteNanos")
        .addModifiers(protectedFinalModifiers)
        .build());
  }

  private void addRefreshAfterWrite() {
    if (!generateFeatures.contains(Feature.REFRESH_WRITE)) {
      return;
    }
    constructor.addStatement("this.refreshAfterWriteNanos = builder.getRefreshAfterWriteNanos()");
    cache.addField(FieldSpec.builder(long.class, "refreshAfterWriteNanos",
        privateVolatileModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("refreshAfterWrite")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("refreshAfterWriteNanos")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return refreshAfterWriteNanos")
        .returns(long.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("setRefreshAfterWriteNanos")
        .addStatement("this.refreshAfterWriteNanos = refreshAfterWriteNanos")
        .addParameter(long.class, "refreshAfterWriteNanos")
        .addModifiers(protectedFinalModifiers)
        .build());
  }

  private void addAccessOrderDeque() {
    if (Feature.usesAccessOrderDeque(parentFeatures)
        || !Feature.usesAccessOrderDeque(generateFeatures)) {
      return;
    }
    constructor.addStatement("this.accessOrderDeque = new $T()", ACCESS_ORDER_DEQUE);
    cache.addField(
        FieldSpec.builder(ACCESS_ORDER_DEQUE, "accessOrderDeque", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("accessOrderDeque")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return accessOrderDeque")
        .returns(ACCESS_ORDER_DEQUE)
        .build());
  }

  private void addWriteOrderDeque() {
    if (Feature.usesWriteOrderDeque(parentFeatures)
        || !Feature.usesWriteOrderDeque(generateFeatures)) {
      return;
    }
    constructor.addStatement("this.writeOrderDeque = new $T()", WRITE_ORDER_DEQUE);
    cache.addField(
        FieldSpec.builder(WRITE_ORDER_DEQUE, "writeOrderDeque", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("writeOrderDeque")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return writeOrderDeque")
        .returns(WRITE_ORDER_DEQUE)
        .build());
  }

  private void addWriteQueue() {
    if (Feature.usesWriteQueue(parentFeatures)
        || !Feature.usesWriteQueue(generateFeatures)) {
      return;
    }
    constructor.addStatement("this.writeQueue = $T.optimistic()", WRITE_QUEUE_TYPE);
    cache.addField(FieldSpec.builder(WRITE_QUEUE, "writeQueue", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("writeQueue")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return writeQueue")
        .returns(WRITE_QUEUE)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("buffersWrites")
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }

  /** Adds the reference strength methods for the key or value. */
  private void addStrength(String collectName, String queueName, TypeName type) {
    cache.addMethod(MethodSpec.methodBuilder(queueName)
        .addModifiers(protectedFinalModifiers)
        .returns(type)
        .addStatement("return $N", queueName)
        .build());
    cache.addField(FieldSpec.builder(type, queueName, privateFinalModifiers)
        .initializer("new $T()", type)
        .build());
    cache.addMethod(MethodSpec.methodBuilder(collectName)
        .addModifiers(protectedFinalModifiers)
        .addStatement("return true")
        .returns(boolean.class)
        .build());
  }
}
