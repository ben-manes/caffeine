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
import java.util.concurrent.ConcurrentLinkedQueue;

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
  static final String PACKAGE_NAME = Specifications.class.getPackage().getName();
  static final String RETIRED_STRONG_KEY = "RETIRED_STRONG_KEY";
  static final String RETIRED_WEAK_KEY = "RETIRED_WEAK_KEY";
  static final String DEAD_STRONG_KEY = "DEAD_STRONG_KEY";
  static final String DEAD_WEAK_KEY = "DEAD_WEAK_KEY";

  static final TypeVariableName kTypeVar = TypeVariableName.get("K");
  static final TypeVariableName vTypeVar = TypeVariableName.get("V");
  static final TypeName kRefQueueType = ParameterizedTypeName.get(
      ClassName.get(ReferenceQueue.class), kTypeVar);
  static final TypeName vRefQueueType = ParameterizedTypeName.get(
      ClassName.get(ReferenceQueue.class), vTypeVar);
  static final ClassName nodeType = ClassName.get(PACKAGE_NAME, "Node");
  static final TypeName lookupKeyType = ParameterizedTypeName.get(ClassName.get(
      PACKAGE_NAME + ".References", "LookupKeyReference"), kTypeVar);
  static final TypeName referenceKeyType = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), kTypeVar);
  static final TypeName rawReferenceKeyType = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), ClassName.get(Object.class));

  static final ParameterSpec keySpec = ParameterSpec.builder(kTypeVar, "key").build();
  static final ParameterSpec keyRefSpec =
      ParameterSpec.builder(Object.class, "keyReference").build();
  static final ParameterSpec keyRefQueueSpec =
      ParameterSpec.builder(kRefQueueType, "keyReferenceQueue").build();

  static final ParameterSpec valueSpec = ParameterSpec.builder(vTypeVar, "value").build();
  static final ParameterSpec valueRefQueueSpec =
      ParameterSpec.builder(vRefQueueType, "valueReferenceQueue").build();

  static final ParameterSpec weightSpec = ParameterSpec.builder(int.class, "weight").build();
  static final TypeName NODE = ParameterizedTypeName.get(nodeType, kTypeVar, vTypeVar);
  static final TypeName UNSAFE_ACCESS =
      ClassName.get("com.github.benmanes.caffeine.base", "UnsafeAccess");

  static final TypeName BUILDER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "Caffeine"), kTypeVar, vTypeVar);
  static final ParameterSpec BUILDER_PARAM =
      ParameterSpec.builder(BUILDER, "builder").build();
  static final TypeName BOUNDED_LOCAL_CACHE = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "BoundedLocalCache"), kTypeVar, vTypeVar);

  static final TypeName CACHE_LOADER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "CacheLoader"), TypeVariableName.get("? super K"), vTypeVar);
  static final ParameterSpec CACHE_LOADER_PARAM =
      ParameterSpec.builder(CACHE_LOADER, "cacheLoader").build();

  static final TypeName REMOVAL_LISTENER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "RemovalListener"), kTypeVar, vTypeVar);

  static final TypeName STATS_COUNTER = ClassName.get(PACKAGE_NAME + ".stats", "StatsCounter");

  static final TypeName TICKER = ClassName.get(PACKAGE_NAME, "Ticker");

  static final TypeName WEIGHER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "Weigher"),
      TypeVariableName.get("? super K"),
      TypeVariableName.get("? super V"));

  static final TypeName ACCESS_ORDER_DEQUE = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "AccessOrderDeque"), NODE);

  static final TypeName WRITE_ORDER_DEQUE = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "WriteOrderDeque"), NODE);

  static final TypeName WRITE_QUEUE = ParameterizedTypeName.get(
      ClassName.get(ConcurrentLinkedQueue.class), ClassName.get(Runnable.class));

  private Specifications() {}

  /** Returns the offset constant to this variable. */
  static String offsetName(String varName) {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, varName) + "_OFFSET";
  }

  /** Creates a static field with an Unsafe address offset. */
  static FieldSpec newFieldOffset(String className, String varName) {
    String name = offsetName(varName);
    return FieldSpec
        .builder(long.class, name, Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL)
        .initializer("$T.objectFieldOffset($T.class, $S)", UNSAFE_ACCESS,
            ClassName.bestGuess(className), varName).build();
  }
}
