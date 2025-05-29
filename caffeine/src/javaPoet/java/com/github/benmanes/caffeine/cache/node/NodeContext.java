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
import static com.github.benmanes.caffeine.cache.Specifications.keyRefSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keySpec;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Feature;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeContext {
  final boolean isFinal;
  final String className;
  final TypeName superClass;
  final TypeSpec.Builder nodeSubtype;
  final Set<String> suppressedWarnings;
  final MethodSpec.Builder constructorByKey;
  final ImmutableSet<Feature> parentFeatures;
  final MethodSpec.Builder constructorDefault;
  final MethodSpec.Builder constructorByKeyRef;
  final ImmutableSet<Feature> generateFeatures;
  final List<Consumer<CodeBlock.Builder>> varHandles;

  public NodeContext(TypeName superClass, String className, boolean isFinal,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    this.isFinal = isFinal;
    this.varHandles = new ArrayList<>();
    this.suppressedWarnings = new TreeSet<>();
    this.className = requireNonNull(className);
    this.superClass = requireNonNull(superClass);
    this.nodeSubtype = TypeSpec.classBuilder(className);
    this.constructorDefault = MethodSpec.constructorBuilder();
    this.parentFeatures = Sets.immutableEnumSet(parentFeatures);
    this.generateFeatures = Sets.immutableEnumSet(generateFeatures);
    this.constructorByKey = MethodSpec.constructorBuilder().addParameter(keySpec);
    this.constructorByKeyRef = MethodSpec.constructorBuilder().addParameter(keyRefSpec);
  }

  public TypeSpec build() {
    return nodeSubtype.build();
  }

  public Modifier[] publicFinalModifiers() {
    return isFinal
        ? new Modifier[] { Modifier.PUBLIC }
        : new Modifier[] { Modifier.PUBLIC, Modifier.FINAL };
  }

  public boolean isBaseClass() {
    return superClass.equals(ClassName.OBJECT);
  }

  public Strength keyStrength() {
    return Strength.of(generateFeatures.asList().get(0));
  }

  public Strength valueStrength() {
    return Strength.of(generateFeatures.asList().get(1));
  }

  public boolean isStrongKeys() {
    return parentFeatures.contains(Feature.STRONG_KEYS)
        || generateFeatures.contains(Feature.STRONG_KEYS);
  }

  public boolean isStrongValues() {
    return parentFeatures.contains(Feature.STRONG_VALUES)
        || generateFeatures.contains(Feature.STRONG_VALUES);
  }

  public ParameterizedTypeName keyReferenceType() {
    checkState(generateFeatures.contains(Feature.WEAK_KEYS));
    return ParameterizedTypeName.get(
        ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), kTypeVar);
  }

  public ParameterizedTypeName valueReferenceType() {
    checkState(!generateFeatures.contains(Feature.STRONG_VALUES));
    String clazz = generateFeatures.contains(Feature.WEAK_VALUES)
        ? "WeakValueReference"
        : "SoftValueReference";
    return ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME + ".References", clazz), vTypeVar);
  }

  /** Creates a VarHandle to the instance field. */
  public void addVarHandle(String varName, TypeName type) {
    String fieldName = varHandleName(varName);
    nodeSubtype.addField(FieldSpec.builder(VarHandle.class, fieldName,
        Modifier.PROTECTED, Modifier.STATIC, Modifier.FINAL).build());
    Consumer<CodeBlock.Builder> statement = builder -> builder
        .addStatement("$L = lookup.findVarHandle($T.class, $L.$L, $T.class)", fieldName,
            ClassName.bestGuess(className), NODE_FACTORY.rawType().simpleName(),
            fieldName, type);
    varHandles.add(statement);
  }

  /** Creates an accessor that returns the unwrapped variable. */
  public MethodSpec newGetter(Strength strength,
      TypeName varType, String varName, Visibility visibility) {
    var getter = MethodSpec.methodBuilder("get" + capitalize(varName))
        .addModifiers(publicFinalModifiers())
        .returns(varType);
    if (strength == Strength.STRONG) {
      switch (visibility) {
        case OPAQUE: {
          var template = String.format(US, "return (%s) $L.getOpaque(this)",
              varType.isPrimitive() ? "$L" : "$T");
          getter.addStatement(template, varType, varHandleName(varName));
          break;
        }
        case PLAIN: {
          var template = String.format(US, "return (%s) $L.get(this)",
              varType.isPrimitive() ? "$L" : "$T");
          getter.addStatement(template, varType, varHandleName(varName));
          break;
        }
        case VOLATILE:
          getter.addStatement("return $N", varName);
          break;
      }
    } else {
      switch (visibility) {
        case OPAQUE:
          throw new IllegalArgumentException();
        case PLAIN:
          getter.addStatement("return (($T<$T>) $L.get(this)).get()",
              Reference.class, varType, varHandleName(varName));
          break;
        case VOLATILE:
          getter.addStatement("return $N.get()", varName);
          break;
      }
    }
    return getter.build();
  }

  /** Creates a mutator to the variable. */
  public MethodSpec newSetter(TypeName varType, String varName, Visibility visibility) {
    String methodName = "set" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    var setter = MethodSpec.methodBuilder(methodName)
        .addModifiers(publicFinalModifiers())
        .addParameter(varType, varName);
    switch (visibility) {
      case OPAQUE:
        setter.addStatement("$L.setOpaque(this, $N)", varHandleName(varName), varName);
        break;
      case PLAIN:
        setter.addStatement("$L.set(this, $N)", varHandleName(varName), varName);
        break;
      case VOLATILE:
        setter.addStatement("this.$N = $N", varName, varName);
        break;
    }
    return setter.build();
  }

  /** Returns the name of the VarHandle to this variable. */
  public static String varHandleName(String varName) {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, varName);
  }

  public enum Visibility {
    PLAIN, OPAQUE, VOLATILE
  }

  public enum Strength {
    STRONG, WEAK, SOFT;

    static Strength of(Feature feature) {
      for (var strength : Strength.values()) {
        if (feature.name().startsWith(strength.name())) {
          return strength;
        }
      }
      throw new IllegalStateException("No strength for " + feature);
    }
  }
}
