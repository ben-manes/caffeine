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

import static com.github.benmanes.caffeine.cache.Specifications.NODE_FACTORY;
import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.util.function.Consumer;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.google.common.base.CaseFormat;
import com.google.common.collect.Iterables;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
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

  @SuppressWarnings("NullAway.Init")
  NodeRule() {}

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

  protected ParameterizedTypeName keyReferenceType() {
    checkState(context.generateFeatures.contains(Feature.WEAK_KEYS));
    return ParameterizedTypeName.get(
        ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), kTypeVar);
  }

  protected ParameterizedTypeName valueReferenceType() {
    checkState(!context.generateFeatures.contains(Feature.STRONG_VALUES));
    String clazz = context.generateFeatures.contains(Feature.WEAK_VALUES)
        ? "WeakValueReference"
        : "SoftValueReference";
    return ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME + ".References", clazz), vTypeVar);
  }

  /** Returns the name of the VarHandle to this variable. */
  protected String varHandleName(String varName) {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, varName);
  }

  /** Creates a VarHandle to the instance field. */
  public void addVarHandle(String varName, TypeName type) {
    String fieldName = varHandleName(varName);
    context.nodeSubtype.addField(FieldSpec.builder(VarHandle.class, fieldName,
        Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL).build());
    Consumer<CodeBlock.Builder> statement = builder -> builder
        .addStatement("$L = lookup.findVarHandle($T.class, $L.$L, $T.class)", fieldName,
            ClassName.bestGuess(context.className), NODE_FACTORY.rawType.simpleName(),
            fieldName, type);
    context.varHandles.add(statement);
  }

  /** Creates an accessor that returns the reference. */
  protected final MethodSpec newGetRef(String varName) {
    MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + capitalize(varName) + "Reference")
        .addModifiers(context.publicFinalModifiers())
        .returns(Object.class);
    getter.addStatement("return $L.get(this)", varHandleName(varName));
    return getter.build();
  }

  /** Creates an accessor that returns the unwrapped variable. */
  protected final MethodSpec newGetter(Strength strength, TypeName varType,
      String varName, Visibility visibility) {
    MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + capitalize(varName))
        .addModifiers(context.publicFinalModifiers())
        .returns(varType);
    if (strength == Strength.STRONG) {
      if (visibility.isPlain) {
        if (varType.isPrimitive()) {
          getter.addStatement("return ($L) $L.get(this)",
              varType.toString(), varHandleName(varName));
        } else {
          getter.addStatement("return ($T) $L.get(this)", varType, varHandleName(varName));
        }
      } else {
        getter.addStatement("return $N", varName);
      }
    } else {
      if (visibility.isPlain) {
        getter.addStatement("return (($T<$T>) $L.get(this)).get()",
            Reference.class, varType, varHandleName(varName));
      } else {
        getter.addStatement("return $N.get()", varName);
      }
    }
    return getter.build();
  }

  /** Creates a mutator to the variable. */
  protected final MethodSpec newSetter(TypeName varType, String varName, Visibility visibility) {
    String methodName = "set" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    MethodSpec.Builder setter = MethodSpec.methodBuilder(methodName)
        .addModifiers(context.publicFinalModifiers())
        .addParameter(varType, varName);
    if (visibility.isPlain) {
      setter.addStatement("$L.set(this, $N)", varHandleName(varName), varName);
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
    STRONG, WEAK, SOFT;
  }

  protected enum Visibility {
    IMMEDIATE(false), PLAIN(true);

    final boolean isPlain;

    Visibility(boolean mode) {
      this.isPlain = mode;
    }
  }
}
