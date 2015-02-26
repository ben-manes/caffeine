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
import static com.github.benmanes.caffeine.cache.Specifications.WEIGHER;
import static com.github.benmanes.caffeine.cache.Specifications.WRITE_ORDER_DEQUE;
import static com.github.benmanes.caffeine.cache.Specifications.WRITE_QUEUE;
import static com.github.benmanes.caffeine.cache.Specifications.kRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
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
  private final Modifier[] privateFinalModifiers = { Modifier.PRIVATE, Modifier.FINAL };

  private final TypeSpec.Builder cache;
  private final MethodSpec.Builder constructor;

  private final Set<Feature> parentFeatures;
  private final Set<Feature> generateFeatures;

  LocalCacheGenerator(TypeName superClass, String className,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    this.constructor = MethodSpec.constructorBuilder();
    this.cache = TypeSpec.classBuilder(className)
        .superclass(superClass)
        .addModifiers(Modifier.STATIC);
    this.parentFeatures = parentFeatures;
    this.generateFeatures = generateFeatures;
  }

  public TypeSpec generate() {
    cache
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar);
    constructor
        .addParameter(BUILDER_PARAM)
        .addParameter(CACHE_LOADER_PARAM)
        .addParameter(boolean.class, "async")
        .addStatement("super(builder, cacheLoader, async)");

    addKeyStrength();
    addValueStrength();
    addCacheLoader();
    addRemovalListener();
    addExecutor();
    addStats();
    addTicker();
    addMaximum();
    addWeigher();
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
        .addStatement("return removalListener")
        .addModifiers(Modifier.PUBLIC)
        .returns(REMOVAL_LISTENER)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("hasRemovalListener")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
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
        .addStatement("return executor")
        .addModifiers(Modifier.PUBLIC)
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
        .addStatement("return cacheLoader")
        .addModifiers(Modifier.PROTECTED)
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
        .addStatement("return statsCounter")
        .addModifiers(Modifier.PUBLIC)
        .returns(STATS_COUNTER)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("isRecordingStats")
        .addStatement("return true")
        .addModifiers(Modifier.PUBLIC)
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
        .addStatement("return ticker")
        .addModifiers(Modifier.PUBLIC)
        .returns(TICKER)
        .build());
  }

  private void addMaximum() {
    if (Feature.usesMaximum(parentFeatures) || !Feature.usesMaximum(generateFeatures)) {
      return;
    }
    cache.addMethod(MethodSpec.methodBuilder("evicts")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .returns(boolean.class)
        .build());
  }

  private void addWeigher() {
    if (!generateFeatures.contains(Feature.MAXIMUM_WEIGHT)) {
      return;
    }
    constructor.addStatement("this.weigher = builder.getWeigher(async)");
    cache.addField(FieldSpec.builder(WEIGHER, "weigher", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("weigher")
        .addStatement("return weigher")
        .addModifiers(Modifier.PROTECTED)
        .returns(WEIGHER)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("isWeighted")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .returns(boolean.class)
        .build());
  }

  private void addExpireAfterAccess() {
    if (!generateFeatures.contains(Feature.EXPIRE_ACCESS)) {
      return;
    }
    constructor.addStatement("this.expiresAfterAccessNanos = builder.getExpiresAfterAccessNanos()");
    cache.addField(FieldSpec.builder(long.class, "expiresAfterAccessNanos",
        Modifier.PRIVATE, Modifier.VOLATILE).build());
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterAccess")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .returns(boolean.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterAccessNanos")
        .addStatement("return expiresAfterAccessNanos")
        .addModifiers(Modifier.PROTECTED)
        .returns(long.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("setExpiresAfterAccessNanos")
        .addStatement("this.expiresAfterAccessNanos = expiresAfterAccessNanos")
        .addParameter(long.class, "expiresAfterAccessNanos")
        .addModifiers(Modifier.PROTECTED)
        .build());
  }

  private void addExpireAfterWrite() {
    if (!generateFeatures.contains(Feature.EXPIRE_WRITE)) {
      return;
    }
    constructor.addStatement("this.expiresAfterWriteNanos = builder.getExpiresAfterWriteNanos()");
    cache.addField(FieldSpec.builder(long.class, "expiresAfterWriteNanos",
        Modifier.PRIVATE, Modifier.VOLATILE).build());
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterWrite")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .returns(boolean.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterWriteNanos")
        .addStatement("return expiresAfterWriteNanos")
        .addModifiers(Modifier.PROTECTED)
        .returns(long.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("setExpiresAfterWriteNanos")
        .addStatement("this.expiresAfterWriteNanos = expiresAfterWriteNanos")
        .addParameter(long.class, "expiresAfterWriteNanos")
        .addModifiers(Modifier.PROTECTED)
        .build());
  }

  private void addRefreshAfterWrite() {
    if (!generateFeatures.contains(Feature.REFRESH_WRITE)) {
      return;
    }
    constructor.addStatement("this.refreshAfterWriteNanos = builder.getRefreshAfterWriteNanos()");
    cache.addField(FieldSpec.builder(long.class, "refreshAfterWriteNanos",
        Modifier.PRIVATE, Modifier.VOLATILE).build());
    cache.addMethod(MethodSpec.methodBuilder("refreshAfterWrite")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .returns(boolean.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("refreshAfterWriteNanos")
        .addStatement("return refreshAfterWriteNanos")
        .addModifiers(Modifier.PROTECTED)
        .returns(long.class)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("setRefreshAfterWriteNanos")
        .addStatement("this.refreshAfterWriteNanos = refreshAfterWriteNanos")
        .addParameter(long.class, "refreshAfterWriteNanos")
        .addModifiers(Modifier.PROTECTED)
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
        .addStatement("return accessOrderDeque")
        .addModifiers(Modifier.PROTECTED)
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
        .addStatement("return writeOrderDeque")
        .addModifiers(Modifier.PROTECTED)
        .returns(WRITE_ORDER_DEQUE)
        .build());
  }

  private void addWriteQueue() {
    if (Feature.usesWriteQueue(parentFeatures)
        || !Feature.usesWriteQueue(generateFeatures)) {
      return;
    }
    constructor.addStatement("this.writeQueue = new $T()", WRITE_QUEUE);
    cache.addField(FieldSpec.builder(WRITE_QUEUE, "writeQueue", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("writeQueue")
        .addStatement("return writeQueue")
        .addModifiers(Modifier.PROTECTED)
        .returns(WRITE_QUEUE)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("buffersWrites")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .returns(boolean.class)
        .build());
  }

  /** Adds the reference strength methods for the key or value. */
  private void addStrength(String collectName, String queueName, TypeName type) {
    cache.addMethod(MethodSpec.methodBuilder(queueName)
        .addModifiers(Modifier.PROTECTED)
        .returns(type)
        .addStatement("return $N", queueName)
        .build());
    cache.addField(FieldSpec.builder(type, queueName, privateFinalModifiers)
        .initializer("new $T()", type)
        .build());
    cache.addMethod(MethodSpec.methodBuilder(collectName)
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .returns(boolean.class)
        .build());
  }
}
