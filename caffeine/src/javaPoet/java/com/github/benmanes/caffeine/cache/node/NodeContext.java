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
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keySpec;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;
import static org.apache.commons.lang3.StringUtils.capitalize;

import java.lang.ref.Reference;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Feature;
import com.github.benmanes.caffeine.cache.RuleContext;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeContext extends RuleContext {
  final MethodSpec.Builder constructorByKeyRef;
  final MethodSpec.Builder constructorDefault;
  final MethodSpec.Builder constructorByKey;

  public NodeContext(TypeName superClass, String className, boolean isFinal,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    super(superClass, className, isFinal, parentFeatures, generateFeatures);
    this.constructorByKeyRef = MethodSpec.constructorBuilder().addParameter(keyRefSpec);
    this.constructorByKey = MethodSpec.constructorBuilder().addParameter(keySpec);
    this.constructorDefault = MethodSpec.constructorBuilder();
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
