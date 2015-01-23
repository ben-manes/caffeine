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

import static com.github.benmanes.caffeine.cache.NodeSpec.NODE;
import static com.github.benmanes.caffeine.cache.NodeSpec.kType;
import static com.github.benmanes.caffeine.cache.NodeSpec.kTypeVar;
import static com.github.benmanes.caffeine.cache.NodeSpec.keySpec;
import static com.github.benmanes.caffeine.cache.NodeSpec.nodeType;
import static com.github.benmanes.caffeine.cache.NodeSpec.vType;
import static com.github.benmanes.caffeine.cache.NodeSpec.vTypeVar;
import static com.github.benmanes.caffeine.cache.NodeSpec.valueSpec;

import java.lang.ref.Reference;
import java.lang.reflect.Type;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.NodeSpec.Strength;
import com.google.common.base.CaseFormat;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.Types;

/**
 * Generates a node implementation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeImplGenerator {
  private static final Type UNSAFE_ACCESS =
      ClassName.get("com.github.benmanes.caffeine.base", "UnsafeAccess");
  private static final AnnotationSpec UNUSED = AnnotationSpec.builder(SuppressWarnings.class)
      .addMember("value", "$S", "unused").build();

  /** Returns an node class implementation optimized for the provided configuration. */
  public TypeSpec createNodeType(String className, String enumName, Strength keyStrength,
      Strength valueStrength, boolean expireAfterAccess, boolean expireAfterWrite, boolean maximum,
      boolean weighed) {
    TypeSpec.Builder nodeSubtype = TypeSpec.classBuilder(className)
        .addSuperinterface(Types.parameterizedType(nodeType, kType, vType))
        .addModifiers(Modifier.STATIC, Modifier.FINAL)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addField(newFieldOffset(className, "value"))
        .addMethod(newGetter(keyStrength, kType, "key", false))
        .addMethod(newGetterRef("key"))
        .addMethod(newGetter(valueStrength, vType, "value", true))
        .addMethod(newSetter(valueStrength, vType, "value", true));
    addConstructor(nodeSubtype, keyStrength, valueStrength);
    addWeight(nodeSubtype, weighed);
    addExpiration(nodeSubtype, className, expireAfterAccess, expireAfterWrite);
    addDeques(nodeSubtype, maximum, expireAfterAccess, expireAfterWrite);
    return nodeSubtype.build();
  }

  /** Adds the constructor to the node type. */
  private void addConstructor(TypeSpec.Builder nodeSubtype,
      Strength keyStrength, Strength valueStrength) {
    MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
        .addParameter(keySpec).addParameter(valueSpec);
    addConstructorAssignment(nodeSubtype, constructor, keyStrength,
        kType, "key", Modifier.FINAL, false);
    addConstructorAssignment(nodeSubtype, constructor, valueStrength,
        vType, "value", Modifier.VOLATILE, true);
    nodeSubtype.addMethod(constructor.build());
  }

  /** Adds a constructor assignment. */
  private void addConstructorAssignment(TypeSpec.Builder type, MethodSpec.Builder constructor,
      Strength strength, Type varType, String varName, Modifier modifier, boolean relaxed) {
    Modifier[] modifiers = { Modifier.PRIVATE, modifier };
    FieldSpec.Builder fieldSpec = (strength == Strength.STRONG)
        ? FieldSpec.builder(varType, varName, modifiers)
        : FieldSpec.builder(Types.parameterizedType(
            ClassName.get(strength.referenceClass), varType), varName, modifiers);
    String prefix = (strength == Strength.STRONG) ? "" : "new ";
    if (relaxed) {
      fieldSpec.addAnnotation(UNUSED);
      constructor.addStatement(
          "$T.UNSAFE.putOrderedObject(this, $N, " + prefix + strength.statementPattern + ")",
          UNSAFE_ACCESS, offsetName(varName), varName);
    } else {
      constructor.addStatement("this.$N = " + prefix + strength.statementPattern, varName, varName);
    }
    type.addField(fieldSpec.build());
  }

  /** Adds weight support, if enabled, to the node type. */
  private void addWeight(TypeSpec.Builder nodeSubtype, boolean weighed) {
    if(weighed) {
      nodeSubtype.addField(int.class, "weight", Modifier.PRIVATE)
          .addMethod(newGetter(Strength.STRONG, int.class, "weight", false))
          .addMethod(newSetter(Strength.STRONG, int.class, "weight", false));
    }
  }

  /** Adds the expiration support, if enabled, to the node type. */
  private void addExpiration(TypeSpec.Builder nodeSubtype, String className,
      boolean expireAfterAccess, boolean expireAfterWrite) {
    if (expireAfterAccess) {
      nodeSubtype.addField(newFieldOffset(className, "accessTime"))
          .addField(FieldSpec.builder(long.class, "accessTime", Modifier.PRIVATE, Modifier.VOLATILE)
              .addAnnotation(UNUSED).build())
          .addMethod(newGetter(Strength.STRONG, long.class, "accessTime", true))
          .addMethod(newSetter(Strength.STRONG, long.class, "accessTime", true));
    }
    if (expireAfterWrite) {
      nodeSubtype.addField(newFieldOffset(className, "writeTime"))
          .addField(FieldSpec.builder(long.class, "writeTime", Modifier.PRIVATE, Modifier.VOLATILE)
              .addAnnotation(UNUSED).build())
          .addMethod(newGetter(Strength.STRONG, long.class, "writeTime", true))
          .addMethod(newSetter(Strength.STRONG, long.class, "writeTime", true));
    }
  }

  /** Adds the access and write deques, if needed, to the type. */
  private void addDeques(TypeSpec.Builder nodeSubtype,
      boolean maximum, boolean expireAfterAccess, boolean expireAfterWrite) {
    if (maximum || expireAfterAccess) {
      addFieldAndGetter(nodeSubtype, NODE, "previousInAccessOrder");
      addFieldAndGetter(nodeSubtype, NODE, "nextInAccessOrder");
    }
    if (expireAfterWrite) {
      addFieldAndGetter(nodeSubtype, NODE, "previousInWriteOrder");
      addFieldAndGetter(nodeSubtype, NODE, "nextInWriteOrder");
    }
  }

  /** Adds a simple field, accessor, and mutator for the variable. */
  private void addFieldAndGetter(TypeSpec.Builder typeSpec, Type varType, String varName) {
    typeSpec.addField(varType, varName, Modifier.PRIVATE)
        .addMethod(newGetter(Strength.STRONG, varType, varName, false))
        .addMethod(newSetter(Strength.STRONG, varType, varName, false));
  }

  /** Creates a static field with an Unsafe address offset. */
  private FieldSpec newFieldOffset(String className, String varName) {
    String name = offsetName(varName);
    return FieldSpec
        .builder(long.class, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .initializer("$T.objectFieldOffset($T.class, $S)", UNSAFE_ACCESS,
            ClassName.bestGuess(className), varName).build();
  }

  /** Returns the offset constant to this variable. */
  private static String offsetName(String varName) {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, varName) + "_OFFSET";
  }

  /** Creates an accessor that returns the reference holding the variable. */
  private MethodSpec newGetterRef(String varName) {
    String methodName = String.format("get%sRef",
        Character.toUpperCase(varName.charAt(0)) + varName.substring(1));
    MethodSpec.Builder getter = MethodSpec.methodBuilder(methodName)
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(Object.class);
    getter.addAnnotation(Nonnull.class);
    getter.addStatement("return $N", varName);
    return getter.build();
  }

  /** Creates an accessor that returns the unwrapped variable. */
  private MethodSpec newGetter(Strength strength, Type varType, String varName, boolean relaxed) {
    String methodName = "get" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    MethodSpec.Builder getter = MethodSpec.methodBuilder(methodName)
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(varType);
    String type;
    boolean primitive = false;
    if ((varType == int.class) || (varType == long.class)) {
      primitive = true;
      getter.addAnnotation(Nonnegative.class);
      type = (varType == int.class) ? "Int" : "Long";
    } else {
      getter.addAnnotation(Nullable.class);
      type = "Object";
    }
    if (strength == Strength.STRONG) {
      if (relaxed) {
        if (primitive) {
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
      if (relaxed) {
        getter.addStatement("return (($T<$T>) $T.UNSAFE.get$N(this, $N)).get()",
            Reference.class, varType, UNSAFE_ACCESS, type, offsetName(varName));
      } else {
        getter.addStatement("return $N.get()", varName);
      }
    }
    if (relaxed && type.equals("Object")) {
      getter.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
          .addMember("value", "$S", "unchecked").build());
    }
    return getter.build();
  }

  /** Creates a mutator to the variable. */
  private MethodSpec newSetter(Strength strength, Type varType, String varName, boolean relaxed) {
    String methodName = "set" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    Type annotation = (varType == int.class) || (varType == long.class)
        ? Nonnegative.class
        : Nullable.class;
    MethodSpec.Builder setter = MethodSpec.methodBuilder(methodName)
        .addParameter(ParameterSpec.builder(varType, varName).addAnnotation(annotation).build())
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC);

    String prefix = (strength == Strength.STRONG) ? "" : "new ";
    if (relaxed) {
      setter.addStatement(
          "$T.UNSAFE.putOrderedObject(this, $N, " + prefix + strength.statementPattern + ")",
          UNSAFE_ACCESS, offsetName(varName), varName);
    } else {
      setter.addStatement("this.$N = " + prefix + strength.statementPattern, varName, varName);
    }
    return setter.build();
  }
}
