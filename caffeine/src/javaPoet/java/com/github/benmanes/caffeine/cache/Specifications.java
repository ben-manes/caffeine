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

import java.lang.ref.ReferenceQueue;

import javax.lang.model.element.Modifier;

import com.google.common.base.CaseFormat;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;

/**
 * Shared constants for a code generation specification.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Specifications {
  public static final String PACKAGE_NAME = Specifications.class.getPackage().getName();
  public static final String RETIRED_STRONG_KEY = "RETIRED_STRONG_KEY";
  public static final String RETIRED_WEAK_KEY = "RETIRED_WEAK_KEY";
  public static final String DEAD_STRONG_KEY = "DEAD_STRONG_KEY";
  public static final String DEAD_WEAK_KEY = "DEAD_WEAK_KEY";

  public static final TypeVariableName kTypeVar = TypeVariableName.get("K");
  public static final TypeVariableName vTypeVar = TypeVariableName.get("V");
  public static final TypeName kRefQueueType = ParameterizedTypeName.get(
      ClassName.get(ReferenceQueue.class), kTypeVar);
  public static final TypeName vRefQueueType = ParameterizedTypeName.get(
      ClassName.get(ReferenceQueue.class), vTypeVar);
  public static final ClassName nodeType = ClassName.get(PACKAGE_NAME, "Node");
  public static final TypeName lookupKeyType =
      ClassName.get(PACKAGE_NAME + ".References", "LookupKeyReference");
  public static final TypeName referenceKeyType = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), kTypeVar);
  public static final TypeName rawReferenceKeyType = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), ClassName.get(Object.class));

  public static final ParameterSpec keySpec = ParameterSpec.builder(kTypeVar, "key").build();
  public static final ParameterSpec keyRefSpec =
      ParameterSpec.builder(Object.class, "keyReference").build();
  public static final ParameterSpec keyRefQueueSpec =
      ParameterSpec.builder(kRefQueueType, "keyReferenceQueue").build();

  public static final ParameterSpec valueSpec = ParameterSpec.builder(vTypeVar, "value").build();
  public static final ParameterSpec valueRefQueueSpec =
      ParameterSpec.builder(vRefQueueType, "valueReferenceQueue").build();

  public static final TypeName UNSAFE_ACCESS =
      ClassName.get("com.github.benmanes.caffeine.cache", "UnsafeAccess");

  public static final TypeName LOCAL_CACHE_FACTORY =
      ClassName.get(PACKAGE_NAME, "LocalCacheFactory");
  public static final ParameterizedTypeName NODE_FACTORY = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "NodeFactory"), kTypeVar, vTypeVar);
  public static final ClassName BUILDER = ClassName.get(PACKAGE_NAME, "Caffeine");
  public static final ParameterSpec BUILDER_PARAM = ParameterSpec.builder(
      ParameterizedTypeName.get(BUILDER, kTypeVar, vTypeVar), "builder").build();
  public static final ParameterizedTypeName BOUNDED_LOCAL_CACHE = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "BoundedLocalCache"), kTypeVar, vTypeVar);
  public static final TypeName NODE = ParameterizedTypeName.get(nodeType, kTypeVar, vTypeVar);

  public static final ParameterizedTypeName CACHE_LOADER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "CacheLoader"), TypeVariableName.get("? super K"), vTypeVar);
  public static final ParameterSpec CACHE_LOADER_PARAM =
      ParameterSpec.builder(CACHE_LOADER, "cacheLoader").build();

  public static final TypeName REMOVAL_LISTENER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "RemovalListener"), kTypeVar, vTypeVar);

  public static final TypeName STATS_COUNTER =
      ClassName.get(PACKAGE_NAME + ".stats", "StatsCounter");
  public static final TypeName TICKER = ClassName.get(PACKAGE_NAME, "Ticker");
  public static final TypeName PACER = ClassName.get(PACKAGE_NAME, "Pacer");

  public static final TypeName ACCESS_ORDER_DEQUE =
      ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME, "AccessOrderDeque"), NODE);
  public static final TypeName WRITE_ORDER_DEQUE =
      ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME, "WriteOrderDeque"), NODE);

  public static final ClassName WRITE_QUEUE_TYPE =
      ClassName.get(PACKAGE_NAME, "MpscGrowableArrayQueue");
  public static final TypeName WRITE_QUEUE =
      ParameterizedTypeName.get(WRITE_QUEUE_TYPE, ClassName.get(Runnable.class));

  public static final TypeName EXPIRY = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "Expiry"), kTypeVar, vTypeVar);
  public static final TypeName TIMER_WHEEL = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "TimerWheel"), kTypeVar, vTypeVar);

  public static final TypeName FREQUENCY_SKETCH = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "FrequencySketch"), kTypeVar);

  private Specifications() {}

  /** Returns the offset constant to this variable. */
  public static String offsetName(String varName) {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, varName) + "_OFFSET";
  }

  /** Creates a public static field with an Unsafe address offset. */
  public static FieldSpec newFieldOffset(String className, String varName) {
    String fieldName = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, varName);
    return FieldSpec
        .builder(long.class, offsetName(varName),
            Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
        .initializer("$T.objectFieldOffset($T.class, $L.$L)", UNSAFE_ACCESS,
            ClassName.bestGuess(className),  LOCAL_CACHE_FACTORY, fieldName)
        .build();
  }
}
