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
package com.github.benmanes.caffeine.cache.node;

import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
import static com.github.benmanes.caffeine.cache.Specifications.UNSAFE_ACCESS;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.offsetName;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.lang.ref.Reference;
import java.util.function.Consumer;

import com.github.benmanes.caffeine.cache.Feature;
import com.google.common.collect.Iterables;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

/**
 * A code generation rule for a node. This class holds the common state and methods for rules to
 * act upon and mutate.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public abstract class NodeRule implements Consumer<NodeContext> {
  protected NodeContext context;

  @Override
  public final void accept(NodeContext context) {
    this.context = context;
    if (applies()) {
      execute();
    }
  }

  /** @return if the rule should be executed. */
  protected abstract boolean applies();

  protected abstract void execute();

  protected boolean isBaseClass() {
    return context.superClass.equals(TypeName.OBJECT);
  }

  protected Strength keyStrength() {
    return strengthOf(Iterables.get(context.generateFeatures, 0));
  }

  protected Strength valueStrength() {
    return strengthOf(Iterables.get(context.generateFeatures, 1));
  }

  protected boolean isStrongKeys() {
    return context.parentFeatures.contains(Feature.STRONG_KEYS)
        || context.generateFeatures.contains(Feature.STRONG_KEYS);
  }

  protected boolean isStrongValues() {
    return context.parentFeatures.contains(Feature.STRONG_VALUES)
        || context.generateFeatures.contains(Feature.STRONG_VALUES);
  }

  protected TypeName keyReferenceType() {
    checkState(context.generateFeatures.contains(Feature.WEAK_KEYS));
    return ParameterizedTypeName.get(
        ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), kTypeVar);
  }

  protected TypeName valueReferenceType() {
    checkState(!context.generateFeatures.contains(Feature.STRONG_VALUES));
    String clazz = context.generateFeatures.contains(Feature.WEAK_VALUES)
        ? "WeakValueReference"
        : "SoftValueReference";
    return ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME + ".References", clazz), vTypeVar);
  }

  /** Creates an accessor that returns the reference. */
  protected final MethodSpec newGetRef(String varName) {
    MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + capitalize(varName) + "Reference")
        .addModifiers(context.publicFinalModifiers())
        .returns(Object.class);
    getter.addStatement("return $T.UNSAFE.getObject(this, $N)",
        UNSAFE_ACCESS, offsetName(varName));
    return getter.build();
  }

  /** Creates an accessor that returns the unwrapped variable. */
  protected final MethodSpec newGetter(Strength strength, TypeName varType,
      String varName, Visibility visibility) {
    MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + capitalize(varName))
        .addModifiers(context.publicFinalModifiers())
        .returns(varType);
    String type;
    if (varType.isPrimitive()) {
      type = varType.equals(TypeName.INT) ? "Int" : "Long";
    } else {
      type = "Object";
    }
    if (strength == Strength.STRONG) {
      if (visibility.isRelaxed) {
        if (varType.isPrimitive()) {
          getter.addStatement("return $T.UNSAFE.get$N(this, $N)",
              UNSAFE_ACCESS, type, offsetName(varName));
        } else {
          getter.addStatement("return ($T) $T.UNSAFE.get$N(this, $N)",
              varType, UNSAFE_ACCESS, type, offsetName(varName));
        }
      } else {
        getter.addStatement("return $N", varName);
      }
    } else {
      if (visibility.isRelaxed) {
        getter.addStatement("return (($T<$T>) $T.UNSAFE.get$N(this, $N)).get()",
            Reference.class, varType, UNSAFE_ACCESS, type, offsetName(varName));
      } else {
        getter.addStatement("return $N.get()", varName);
      }
    }
    return getter.build();
  }

  /** Creates a mutator to the variable. */
  protected final MethodSpec newSetter(TypeName varType, String varName, Visibility visibility) {
    String methodName = "set" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    String type;
    if (varType.isPrimitive()) {
      type = varType.equals(TypeName.INT) ? "Int" : "Long";
    } else {
      type = "Object";
    }
    MethodSpec.Builder setter = MethodSpec.methodBuilder(methodName)
        .addModifiers(context.publicFinalModifiers())
        .addParameter(varType, varName);
    if (visibility.isRelaxed) {
      setter.addStatement("$T.UNSAFE.put$L(this, $N, $N)",
          UNSAFE_ACCESS, type, offsetName(varName), varName);
    } else {
      setter.addStatement("this.$N = $N", varName, varName);
    }

    return setter.build();
  }

  private Strength strengthOf(Feature feature) {
    for (Strength strength : Strength.values()) {
      if (feature.name().startsWith(strength.name())) {
        return strength;
      }
    }
    throw new IllegalStateException("No strength for " + feature);
  }

  protected enum Strength {
    STRONG, WEAK, SOFT,
  }

  protected enum Visibility {
    IMMEDIATE(false), LAZY(true);

    final boolean isRelaxed;

    Visibility(boolean mode) {
      this.isRelaxed = mode;
    }
  }
}
