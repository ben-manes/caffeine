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

import static com.github.benmanes.caffeine.cache.Specifications.DEAD_STRONG_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.DEAD_WEAK_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.NODE;
import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
import static com.github.benmanes.caffeine.cache.Specifications.RETIRED_STRONG_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.RETIRED_WEAK_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.UNSAFE_ACCESS;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keySpec;
import static com.github.benmanes.caffeine.cache.Specifications.newFieldOffset;
import static com.github.benmanes.caffeine.cache.Specifications.nodeType;
import static com.github.benmanes.caffeine.cache.Specifications.offsetName;
import static com.github.benmanes.caffeine.cache.Specifications.vRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.valueRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.valueSpec;
import static com.google.common.base.Preconditions.checkState;

import java.lang.ref.Reference;
import java.util.Objects;
import java.util.Set;

import javax.lang.model.element.Modifier;

import com.google.common.collect.Iterables;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * Generates a node implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeGenerator {
  private final boolean isFinal;
  private final String className;
  private final TypeName superClass;
  private final Set<Feature> parentFeatures;
  private final Set<Feature> generateFeatures;

  private TypeSpec.Builder nodeSubtype;
  private MethodSpec.Builder constructorByKey;
  private MethodSpec.Builder constructorByKeyRef;

  public NodeGenerator(TypeName superClass, String className, boolean isFinal,
      Set<Feature> parentFeatures, Set<Feature> generateFeatures) {
    this.isFinal = isFinal;
    this.className = className;
    this.superClass = superClass;
    this.parentFeatures = parentFeatures;
    this.generateFeatures = generateFeatures;
  }

  /** Returns an node class implementation optimized for the provided configuration. */
  public TypeSpec.Builder createNodeType() {
    makeNodeSubtype();
    makeBaseConstructorByKey();
    makeBaseConstructorByKeyRef();

    addKey();
    addValue();
    addWeight();
    addExpiration();
    addDeques();
    addStateMethods();
    addToString();

    return nodeSubtype
        .addMethod(constructorByKey.build())
        .addMethod(constructorByKeyRef.build());
  }

  private boolean isBaseClass() {
    return superClass.equals(TypeName.OBJECT);
  }

  private boolean isStrongKeys() {
    return parentFeatures.contains(Feature.STRONG_KEYS)
        || generateFeatures.contains(Feature.STRONG_KEYS);
  }

  private boolean isStrongValues() {
    return parentFeatures.contains(Feature.STRONG_VALUES)
        || generateFeatures.contains(Feature.STRONG_VALUES);
  }

  private void makeNodeSubtype() {
    nodeSubtype = TypeSpec.classBuilder(className)
        .addModifiers(Modifier.STATIC)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar);
    if (isFinal) {
      nodeSubtype.addModifiers(Modifier.FINAL);
    }
    if (isBaseClass()) {
      nodeSubtype.addSuperinterface(ParameterizedTypeName.get(nodeType, kTypeVar, vTypeVar));
    } else {
      nodeSubtype.superclass(superClass);
    }
  }

  private void addKey() {
    if (!isBaseClass()) {
      return;
    }
    nodeSubtype
        .addField(newFieldOffset(className, "key"))
        .addField(newKeyField())
        .addMethod(newGetter(keyStrength(), kTypeVar, "key", Visibility.LAZY))
        .addMethod(newGetRef("key"));
    addKeyConstructorAssignment(constructorByKey, false);
    addKeyConstructorAssignment(constructorByKeyRef, true);
  }

  private Strength keyStrength() {
    return strengthOf(Iterables.get(generateFeatures, 0));
  }

  private void addValue() {
    if (!isBaseClass()) {
      return;
    }
    nodeSubtype
        .addField(newFieldOffset(className, "value"))
        .addField(newValueField())
        .addMethod(newGetter(valueStrength(), vTypeVar, "value", Visibility.LAZY))
        .addMethod(newGetRef("value"))
        .addMethod(makeSetValue())
        .addMethod(makeContainsValue());
    addValueConstructorAssignment(constructorByKey);
    addValueConstructorAssignment(constructorByKeyRef);
  }

  private Strength valueStrength() {
    return strengthOf(Iterables.get(generateFeatures, 1));
  }

  /** Creates the setValue method. */
  private MethodSpec makeSetValue() {
    MethodSpec.Builder setter = MethodSpec.methodBuilder("setValue")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addParameter(vTypeVar, "value")
        .addParameter(vRefQueueType, "referenceQueue");

    if (isStrongValues()) {
      setter.addStatement("$T.UNSAFE.putObject(this, $N, $N)",
          UNSAFE_ACCESS, offsetName("value"), "value");
    } else {
      setter.addStatement("(($T<V>) getValueReference()).clear()", Reference.class);
      setter.addStatement("$T.UNSAFE.putObject(this, $N, new $T($L, $N, referenceQueue))",
          UNSAFE_ACCESS, offsetName("value"), valueReferenceType(), "key", "value");
    }

    return setter.build();
  }

  private MethodSpec makeContainsValue() {
    MethodSpec.Builder containsValue = MethodSpec.methodBuilder("containsValue")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addParameter(Object.class, "value")
        .returns(boolean.class);
    if (isStrongValues()) {
      containsValue.addStatement("return $T.equals(value, getValue())", Objects.class);
    } else {
      containsValue.addStatement("return getValue() == value");
    }
    return containsValue.build();
  }

  private FieldSpec newKeyField() {
    Modifier[] modifiers = { Modifier.PROTECTED, Modifier.VOLATILE };
    FieldSpec.Builder fieldSpec = isStrongKeys()
        ? FieldSpec.builder(kTypeVar, "key", modifiers)
        : FieldSpec.builder(keyReferenceType(), "key", modifiers);
    return fieldSpec.build();
  }

  private FieldSpec newValueField() {
    Modifier[] modifiers = { Modifier.PROTECTED, Modifier.VOLATILE };
    FieldSpec.Builder fieldSpec = isStrongValues()
        ? FieldSpec.builder(vTypeVar, "value", modifiers)
        : FieldSpec.builder(valueReferenceType(), "value", modifiers);
    return fieldSpec.build();
  }

  /** Adds the constructor by key to the node type. */
  private void makeBaseConstructorByKey() {
    constructorByKey = MethodSpec.constructorBuilder().addParameter(keySpec);
    constructorByKey.addParameter(keyRefQueueSpec);
    completeBaseConstructor(constructorByKey);
    if (!isBaseClass()) {
      constructorByKey.addStatement(
          "super(key, keyReferenceQueue, value, valueReferenceQueue, weight, now)");
    }
  }

  /** Adds the constructor by key reference to the node type. */
  private void makeBaseConstructorByKeyRef() {
    constructorByKeyRef = MethodSpec.constructorBuilder().addParameter(keyRefSpec);
    completeBaseConstructor(constructorByKeyRef);
    if (!isBaseClass()) {
      constructorByKeyRef.addStatement(
          "super(keyReference, value, valueReferenceQueue, weight, now)");
    }
  }

  private void completeBaseConstructor(MethodSpec.Builder constructor) {
    constructor.addParameter(valueSpec);
    constructor.addParameter(valueRefQueueSpec);
    constructor.addParameter(int.class, "weight");
    constructor.addParameter(long.class, "now");
  }

  /** Adds a constructor assignment. */
  private void addKeyConstructorAssignment(MethodSpec.Builder constructor, boolean isReference) {
    if (isReference || isStrongKeys()) {
      String refAssignment = isStrongKeys()
          ? "(K) keyReference"
          : "(WeakKeyReference<K>) keyReference";
      constructor.addStatement("$T.UNSAFE.putObject(this, $N, $N)",
          UNSAFE_ACCESS, offsetName("key"), isReference ? refAssignment : "key");
    } else {
      constructor.addStatement("$T.UNSAFE.putObject(this, $N, new $T($N, $N))",
          UNSAFE_ACCESS, offsetName("key"), keyReferenceType(), "key", "keyReferenceQueue");
    }
  }

  /** Adds a constructor assignment. */
  private void addValueConstructorAssignment(MethodSpec.Builder constructor) {
    if (isStrongValues()) {
      constructor.addStatement("$T.UNSAFE.putObject(this, $N, $N)",
          UNSAFE_ACCESS, offsetName("value"), "value");
    } else {
      constructor.addStatement("$T.UNSAFE.putObject(this, $N, new $T(this.$N, $N, $N))",
          UNSAFE_ACCESS, offsetName("value"), valueReferenceType(),
          "key", "value", "valueReferenceQueue");
    }
  }

  /** Adds weight support, if enabled, to the node type. */
  private void addWeight() {
    if (generateFeatures.contains(Feature.MAXIMUM_WEIGHT)) {
      nodeSubtype.addField(int.class, "weight", Modifier.PROTECTED)
          .addMethod(newGetter(Strength.STRONG, TypeName.INT, "weight", Visibility.IMMEDIATE))
          .addMethod(newSetter(TypeName.INT, "weight", Visibility.IMMEDIATE));
      addIntConstructorAssignment(constructorByKey, "weight", "weight", Visibility.IMMEDIATE);
      addIntConstructorAssignment(constructorByKeyRef, "weight", "weight", Visibility.IMMEDIATE);
    }
  }

  /** Adds the expiration support, if enabled, to the node type. */
  private void addExpiration() {
    if (generateFeatures.contains(Feature.EXPIRE_ACCESS)) {
      nodeSubtype.addField(newFieldOffset(className, "accessTime"))
          .addField(long.class, "accessTime", Modifier.PROTECTED, Modifier.VOLATILE)
          .addMethod(newGetter(Strength.STRONG, TypeName.LONG,
              "accessTime", Visibility.LAZY))
          .addMethod(newSetter(TypeName.LONG, "accessTime", Visibility.LAZY));
      addLongConstructorAssignment(constructorByKey, "now", "accessTime", Visibility.LAZY);
      addLongConstructorAssignment(constructorByKeyRef, "now", "accessTime", Visibility.LAZY);
    }

    if (!Feature.useWriteTime(parentFeatures) && Feature.useWriteTime(generateFeatures)) {
      nodeSubtype.addField(newFieldOffset(className, "writeTime"))
          .addField(long.class, "writeTime", Modifier.PROTECTED, Modifier.VOLATILE)
          .addMethod(newGetter(Strength.STRONG, TypeName.LONG, "writeTime", Visibility.LAZY))
          .addMethod(newSetter(TypeName.LONG, "writeTime", Visibility.LAZY));
      addLongConstructorAssignment(constructorByKey, "now", "writeTime", Visibility.LAZY);
      addLongConstructorAssignment(constructorByKeyRef, "now", "writeTime", Visibility.LAZY);
    }

    if (generateFeatures.contains(Feature.REFRESH_WRITE)) {
      nodeSubtype.addMethod(MethodSpec.methodBuilder("casWriteTime")
          .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
          .addParameter(long.class, "expect")
          .addParameter(long.class, "update")
          .returns(boolean.class)
          .addStatement("return $T.UNSAFE.compareAndSwapLong(this, $N, $N, $N)",
              UNSAFE_ACCESS, offsetName("writeTime"), "expect", "update")
          .build());
    }
  }

  /** Adds a integer constructor assignment. */
  private void addIntConstructorAssignment(MethodSpec.Builder constructor,
      String param, String field, Visibility visibility) {
    if (visibility.isRelaxed) {
      constructor.addStatement("$T.UNSAFE.putInt(this, $N, $N)",
          UNSAFE_ACCESS, offsetName(field), param);
      constructor.addStatement("this.$N = $N", field, param);
    } else {
      constructor.addStatement("this.$N = $N", field, param);
    }
  }

  /** Adds a long constructor assignment. */
  private void addLongConstructorAssignment(MethodSpec.Builder constructor,
      String param, String field, Visibility visibility) {
    if (visibility.isRelaxed) {
      constructor.addStatement("$T.UNSAFE.putLong(this, $N, $N)",
          UNSAFE_ACCESS, offsetName(field), param);
    } else {
      constructor.addStatement("this.$N = $N", field, param);
    }
  }

  /** Adds the access and write deques, if needed, to the type. */
  private void addDeques() {
    if (!Feature.usesAccessOrderDeque(parentFeatures)
        && Feature.usesAccessOrderDeque(generateFeatures)) {
      addFieldAndGetter(nodeSubtype, NODE, "previousInAccessOrder");
      addFieldAndGetter(nodeSubtype, NODE, "nextInAccessOrder");
    }
    if (!Feature.usesWriteOrderDeque(parentFeatures)
        && Feature.usesWriteOrderDeque(generateFeatures)) {
      addFieldAndGetter(nodeSubtype, NODE, "previousInWriteOrder");
      addFieldAndGetter(nodeSubtype, NODE, "nextInWriteOrder");
    }
  }

  /** Adds a simple field, accessor, and mutator for the variable. */
  private void addFieldAndGetter(TypeSpec.Builder typeSpec, TypeName varType, String varName) {
    typeSpec.addField(varType, varName, Modifier.PROTECTED)
        .addMethod(newGetter(Strength.STRONG, varType, varName, Visibility.IMMEDIATE))
        .addMethod(newSetter(varType, varName, Visibility.IMMEDIATE));
  }

  private void addStateMethods() {
    if (!isBaseClass()) {
      return;
    }

    String retiredArg;
    String deadArg;
    if (keyStrength() == Strength.STRONG) {
      retiredArg = RETIRED_STRONG_KEY;
      deadArg = DEAD_STRONG_KEY;
    } else {
      retiredArg = RETIRED_WEAK_KEY;
      deadArg = DEAD_WEAK_KEY;
    }

    nodeSubtype.addMethod(MethodSpec.methodBuilder("isAlive")
        .addStatement("Object key = getKeyReference()")
        .addStatement("return (key != $L) && (key != $L)", retiredArg, deadArg)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(boolean.class)
        .build());
    addState("isRetired", "retire", retiredArg);
    addState("isDead", "die", deadArg);
  }

  private void addState(String checkName, String actionName, String arg) {
    nodeSubtype.addMethod(MethodSpec.methodBuilder(checkName)
        .addStatement("return (getKeyReference() == $L)", arg)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(boolean.class)
        .build());

    MethodSpec.Builder action = MethodSpec.methodBuilder(actionName)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
    if (keyStrength() != Strength.STRONG) {
      action.addStatement("(($T<K>) getKeyReference()).clear()", Reference.class);
    }
    if (valueStrength() != Strength.STRONG) {
      action.addStatement("(($T<V>) getValueReference()).clear()", Reference.class);
    }
    action.addStatement("$T.UNSAFE.putObject(this, $N, $N)", UNSAFE_ACCESS, offsetName("key"), arg);
    nodeSubtype.addMethod(action.build());
  }

  /** Creates an accessor that returns the reference. */
  private MethodSpec newGetRef(String varName) {
    MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + capitalize(varName) + "Reference")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(Object.class);
    getter.addStatement("return $T.UNSAFE.getObject(this, $N)",
        UNSAFE_ACCESS, offsetName(varName));
    return getter.build();
  }

  /** Creates an accessor that returns the unwrapped variable. */
  private MethodSpec newGetter(Strength strength, TypeName varType,
      String varName, Visibility visibility) {
    MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + capitalize(varName))
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(varType);
    String type;
    if (varType.isPrimitive()) {
      type = (varType == TypeName.INT) ? "Int" : "Long";
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
  private MethodSpec newSetter(TypeName varType, String varName, Visibility visibility) {
    String methodName = "set" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    String type;
    if (varType.isPrimitive()) {
      type = (varType == TypeName.INT) ? "Int" : "Long";
    } else {
      type = "Object";
    }
    MethodSpec.Builder setter = MethodSpec.methodBuilder(methodName)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addParameter(varType, varName);
    if (visibility.isRelaxed) {
      setter.addStatement("$T.UNSAFE.put$L(this, $N, $N)",
          UNSAFE_ACCESS, type, offsetName(varName), varName);
    } else {
      setter.addStatement("this.$N = $N", varName, varName);
    }

    return setter.build();
  }

  /** Generates a toString method with the custom fields. */
  private void addToString() {
    if (!isBaseClass()) {
      return;
    }

    String statement = "return String.format(\"%s=[key=%s, value=%s, weight=%d, accessTimeNS=%,d, "
        + "writeTimeNS=%,d, \"\n+ \"prevInAccess=%s, nextInAccess=%s, prevInWrite=%s, "
        + "nextInWrite=%s]\",\ngetClass().getSimpleName(), getKey(), getValue(), getWeight(), "
        + "getAccessTime(),\ngetWriteTime(), getPreviousInAccessOrder() != null, "
        + "getNextInAccessOrder() != null,\ngetPreviousInWriteOrder() != null, "
        + "getNextInWriteOrder() != null)";

    nodeSubtype.addMethod(MethodSpec.methodBuilder("toString")
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(String.class)
        .addStatement(statement)
        .build());
  }

  private TypeName keyReferenceType() {
    checkState(generateFeatures.contains(Feature.WEAK_KEYS));
    return ParameterizedTypeName.get(
        ClassName.get(PACKAGE_NAME + ".References", "WeakKeyReference"), kTypeVar);
  }

  private TypeName valueReferenceType() {
    checkState(!generateFeatures.contains(Feature.STRONG_VALUES));
    String clazz = generateFeatures.contains(Feature.WEAK_VALUES)
        ? "WeakValueReference"
        : "SoftValueReference";
    return ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME + ".References", clazz), vTypeVar);
  }

  private static String capitalize(String str) {
    return Character.toUpperCase(str.charAt(0)) + str.substring(1);
  }

  Strength strengthOf(Feature feature) {
    for (Strength strength : Strength.values()) {
      if (feature.name().startsWith(strength.name())) {
        return strength;
      }
    }
    throw new IllegalStateException("No strength for " + feature);
  }

  enum Strength {
    STRONG, WEAK, SOFT;
  }

  enum Visibility {
    IMMEDIATE(false), LAZY(true);

    final boolean isRelaxed;
    private Visibility(boolean mode) {
      this.isRelaxed = mode;
    }
  }
}
