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
import static com.github.benmanes.caffeine.cache.Specifications.BOUNDED_LOCAL_CACHE;
import static com.github.benmanes.caffeine.cache.Specifications.BUILDER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.CACHE_LOADER;
import static com.github.benmanes.caffeine.cache.Specifications.CACHE_LOADER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.REMOVAL_LISTENER;
import static com.github.benmanes.caffeine.cache.Specifications.STATS_COUNTER;
import static com.github.benmanes.caffeine.cache.Specifications.TICKER;
import static com.github.benmanes.caffeine.cache.Specifications.WEIGHER;
import static com.github.benmanes.caffeine.cache.Specifications.WRITE_ORDER_DEQUE;
import static com.github.benmanes.caffeine.cache.Specifications.kRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Specifications.Strength;
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

  private final Strength keyStrength;
  private final Strength valueStrength;
  private final boolean cacheLoader;
  private final boolean removalListener;
  private final boolean executor;
  private final boolean stats;
  private final boolean maximum;
  private final boolean weighed;
  private final boolean expireAfterAccess;
  private final boolean expireAfterWrite;
  private final boolean refreshAfterWrite;

  LocalCacheGenerator(String className, Strength keyStrength, Strength valueStrength,
      boolean cacheLoader, boolean removalListener, boolean executor, boolean stats,
      boolean maximum, boolean weighed, boolean expireAfterAccess, boolean expireAfterWrite,
      boolean refreshAfterWrite) {
    this.constructor = MethodSpec.constructorBuilder();
    this.cache = TypeSpec.classBuilder(className);
    this.expireAfterAccess = expireAfterAccess;
    this.refreshAfterWrite = refreshAfterWrite;
    this.expireAfterWrite = expireAfterWrite;
    this.removalListener = removalListener;
    this.valueStrength = valueStrength;
    this.keyStrength = keyStrength;
    this.cacheLoader = cacheLoader;
    this.executor = executor;
    this.maximum = maximum;
    this.weighed = weighed;
    this.stats = stats;
  }

  public TypeSpec generate() {
    cache.addModifiers(Modifier.FINAL)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .superclass(BOUNDED_LOCAL_CACHE);
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
    return cache.addMethod(constructor.build()).build();
  }

  private void addKeyStrength() {
    addStrength(keyStrength, "collectKeys", "keyReferenceQueue", kRefQueueType);
  }

  private void addValueStrength() {
    addStrength(valueStrength, "collectValues", "valueReferenceQueue", vRefQueueType);
  }

  private void addRemovalListener() {
    if (!removalListener) {
      return;
    }
    cache.addField(
        FieldSpec.builder(REMOVAL_LISTENER, "removalListener", privateFinalModifiers).build());
    constructor.addStatement("this.removalListener = builder.getRemovalListener(async)");
    cache.addMethod(MethodSpec.methodBuilder("removalListener")
        .addStatement("return removalListener")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .addAnnotation(Nullable.class)
        .returns(REMOVAL_LISTENER)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("hasRemovalListener")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(boolean.class)
        .build());
  }

  private void addExecutor() {
    if (!executor) {
      return;
    }
    cache.addField(FieldSpec.builder(Executor.class, "executor", privateFinalModifiers).build());
    constructor.addStatement("this.executor = builder.getExecutor()");
    cache.addMethod(MethodSpec.methodBuilder("executor")
        .addStatement("return executor")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(Executor.class)
        .build());
  }

  private void addCacheLoader() {
    if (!cacheLoader) {
      return;
    }
    constructor.addStatement("this.cacheLoader = cacheLoader");
    cache.addField(FieldSpec.builder(CACHE_LOADER, "cacheLoader", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("cacheLoader")
        .addStatement("return cacheLoader")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .addAnnotation(Nullable.class)
        .returns(CACHE_LOADER)
        .build());
  }

  private void addStats() {
    if (!stats) {
      return;
    }
    constructor.addStatement("this.statsCounter = builder.getStatsCounterSupplier().get()");
    cache.addField(FieldSpec.builder(STATS_COUNTER, "statsCounter", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("statsCounter")
        .addStatement("return statsCounter")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(STATS_COUNTER)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("isRecordingStats")
        .addStatement("return true")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(boolean.class)
        .build());
  }

  private void addTicker() {
    boolean useTicker = expireAfterAccess || expireAfterWrite || refreshAfterWrite || stats;
    if (!useTicker) {
      return;
    }
    constructor.addStatement("this.ticker = builder.getTicker()");
    cache.addField(FieldSpec.builder(TICKER, "ticker", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("ticker")
        .addStatement("return ticker")
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(Override.class)
        .returns(TICKER)
        .build());
  }

  private void addMaximum() {
    if (!maximum) {
      return;
    }
    cache.addMethod(MethodSpec.methodBuilder("evicts")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(boolean.class)
        .build());
  }

  private void addWeigher() {
    if (!maximum && !weighed) {
      return;
    }
    constructor.addStatement("this.weigher = builder.getWeigher(async)");
    cache.addField(FieldSpec.builder(WEIGHER, "weigher", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("weigher")
        .addStatement("return weigher")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(WEIGHER)
        .build());
    cache.addMethod(MethodSpec.methodBuilder("isWeighted")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(boolean.class)
        .build());
  }

  private void addExpireAfterAccess() {
    if (!expireAfterAccess) {
      return;
    }
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterAccess")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(boolean.class)
        .build());
  }

  private void addExpireAfterWrite() {
    if (!expireAfterWrite) {
      return;
    }
    cache.addMethod(MethodSpec.methodBuilder("expiresAfterWrite")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(boolean.class)
        .build());
  }

  private void addRefreshAfterWrite() {
    if (!refreshAfterWrite) {
      return;
    }
    cache.addMethod(MethodSpec.methodBuilder("refreshAfterWrite")
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(boolean.class)
        .build());
  }

  private void addAccessOrderDeque() {
    boolean useAccessOrderDeque = maximum || expireAfterAccess;
    if (!useAccessOrderDeque) {
      return;
    }
    constructor.addStatement("this.accessOrderDeque = new $T()", ACCESS_ORDER_DEQUE);
    cache.addField(
        FieldSpec.builder(ACCESS_ORDER_DEQUE, "accessOrderDeque", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("accessOrderDeque")
        .addStatement("return accessOrderDeque")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(ACCESS_ORDER_DEQUE)
        .build());
  }

  private void addWriteOrderDeque() {
    boolean useAccessOrderDeque = expireAfterWrite || refreshAfterWrite;
    if (!useAccessOrderDeque) {
      return;
    }
    constructor.addStatement("this.writeOrderDeque = new $T()", WRITE_ORDER_DEQUE);
    cache.addField(
        FieldSpec.builder(WRITE_ORDER_DEQUE, "writeOrderDeque", privateFinalModifiers).build());
    cache.addMethod(MethodSpec.methodBuilder("writeOrderDeque")
        .addStatement("return writeOrderDeque")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(WRITE_ORDER_DEQUE)
        .build());
  }

  /** Adds the weak/soft methods for the key or value, if needed. */
  private void addStrength(Strength strength, String collectName, String queueName, TypeName type) {
    if (strength == Strength.STRONG) {
      return;
    }
    cache.addMethod(MethodSpec.methodBuilder(queueName)
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .addAnnotation(Nullable.class)
        .returns(type)
        .addStatement("return $N", queueName)
        .build());
    cache.addField(FieldSpec.builder(type, queueName, privateFinalModifiers)
        .initializer("new $T()", type)
        .build());
    cache.addMethod(MethodSpec.methodBuilder(collectName)
        .addStatement("return true")
        .addModifiers(Modifier.PROTECTED)
        .addAnnotation(Override.class)
        .returns(boolean.class)
        .build());
  }
}
