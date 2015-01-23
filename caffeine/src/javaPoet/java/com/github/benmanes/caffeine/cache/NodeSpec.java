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

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.Types;

/**
 * Shared constants for a node specification.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeSpec {
  static final String PACKAGE_NAME = NodeFactoryGenerator.class.getPackage().getName();

  static final TypeVariable<?> kTypeVar = Types.typeVariable("K");
  static final TypeVariable<?> vTypeVar = Types.typeVariable("V");
  static final Type kType = ClassName.get(PACKAGE_NAME, "K");
  static final Type vType = ClassName.get(PACKAGE_NAME, "V");
  static final Type nodeType = ClassName.get(PACKAGE_NAME, "Node");
  static final ParameterSpec keySpec = ParameterSpec.builder(kType, "key")
      .addAnnotation(Nonnull.class).build();
  static final ParameterSpec valueSpec = ParameterSpec.builder(vType, "value")
      .addAnnotation(Nonnull.class).build();
  static final ParameterSpec weightSpec = ParameterSpec.builder(int.class, "weight")
      .addAnnotation(Nonnegative.class).build();
  static final Type NODE = Types.parameterizedType(nodeType, kType, vType);

  enum Strength {
    STRONG("$N", null),
    WEAK("WeakReference<>($N)", WeakReference.class),
    SOFT("SoftReference<>($N)", SoftReference.class);

    public final String statementPattern;
    public final Class<?> referenceClass;

    private Strength(String statementPattern, Class<?> referenceClass) {
      this.statementPattern = statementPattern;
      this.referenceClass = referenceClass;
    }
  }

  private NodeSpec() {}
}
