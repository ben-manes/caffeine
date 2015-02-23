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

import static com.google.common.base.Preconditions.checkState;

import java.lang.ref.ReferenceQueue;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
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

  static final ParameterSpec keySpec = ParameterSpec.builder(kTypeVar, "key")
      .addAnnotation(Nonnull.class).build();
  static final ParameterSpec keyRefSpec = ParameterSpec.builder(Object.class, "keyReference")
      .addAnnotation(Nonnull.class).build();
  static final ParameterSpec keyRefQueueSpec =
      ParameterSpec.builder(kRefQueueType, "keyReferenceQueue").build();

  static final ParameterSpec valueSpec = ParameterSpec.builder(vTypeVar, "value")
      .addAnnotation(Nonnull.class).build();
  static final ParameterSpec valueRefQueueSpec =
      ParameterSpec.builder(vRefQueueType, "valueReferenceQueue").build();

  static final ParameterSpec weightSpec = ParameterSpec.builder(int.class, "weight")
      .addAnnotation(Nonnegative.class).build();
  static final TypeName NODE = ParameterizedTypeName.get(nodeType, kTypeVar, vTypeVar);
  static final TypeName UNSAFE_ACCESS =
      ClassName.get("com.github.benmanes.caffeine.base", "UnsafeAccess");
  static final AnnotationSpec UNUSED =
      AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "$S", "unused").build();

  static final TypeName BUILDER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "Caffeine"), kTypeVar, vTypeVar);
  static final ParameterSpec BUILDER_PARAM =
      ParameterSpec.builder(BUILDER, "builder").addAnnotation(Nonnull.class).build();
  static final TypeName BOUNDED_LOCAL_CACHE = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "BoundedLocalCache"), kTypeVar, vTypeVar);

  static final TypeName CACHE_LOADER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "CacheLoader"), TypeVariableName.get("? super K"), vTypeVar);
  static final ParameterSpec CACHE_LOADER_PARAM =
      ParameterSpec.builder(CACHE_LOADER, "cacheLoader").addAnnotation(Nullable.class).build();

  static final TypeName REMOVAL_LISTENER = ParameterizedTypeName.get(
      ClassName.get(PACKAGE_NAME, "RemovalListener"), kTypeVar, vTypeVar);

  static final TypeName STATS_COUNTER = ClassName.get(PACKAGE_NAME + ".stats", "StatsCounter");


  enum Visibility {
    IMMEDIATE(false), LAZY(true);

    final boolean isRelaxed;
    private Visibility(boolean mode) {
      this.isRelaxed = mode;
    }
  }

  enum Strength {
    STRONG, WEAK, SOFT;

    public TypeName keyReferenceType() {
      checkState(this == WEAK);
      return ParameterizedTypeName.get(
          ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), kTypeVar);
    }

    public TypeName valueReferenceType() {
      checkState(this != STRONG);
      String clazz = (this == WEAK) ? "WeakValueReference" : "SoftValueReference";
      return ParameterizedTypeName.get(
          ClassName.get(PACKAGE_NAME + ".References", clazz), vTypeVar);
    }
  }

  private Specifications() {}
}
