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

import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.NodeSpec.Strength;
import com.google.common.base.CaseFormat;
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

  public TypeSpec createNodeType(String className, String enumName, Strength keyStrength,
      Strength valueStrength, boolean expireAfterAccess, boolean expireAfterWrite, boolean maximum,
      boolean weighed) {
    TypeSpec.Builder nodeSubtype = TypeSpec.classBuilder(className)
        .addSuperinterface(Types.parameterizedType(nodeType, kType, vType))
        .addModifiers(Modifier.STATIC, Modifier.FINAL)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addField(newFieldOffset(className, "value"))
        .addMethod(newGetter(keyStrength, kType, "key"))
        .addMethod(newGetterRef("key"))
        .addMethod(newGetter(valueStrength, vType, "value"))
        .addMethod(newSetter(valueStrength, vType, "value", true));
    MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
        .addParameter(keySpec).addParameter(valueSpec);
    addConstructorAssignment(nodeSubtype, constructor, keyStrength, kType, "key", Modifier.FINAL);
    addConstructorAssignment(nodeSubtype, constructor,
        valueStrength, vType, "value", Modifier.VOLATILE);

    if(weighed) {
      nodeSubtype.addField(int.class, "weight", Modifier.PRIVATE)
          .addMethod(newGetter(Strength.STRONG, int.class, "weight"))
          .addMethod(newSetter(Strength.STRONG, int.class, "weight", false));
    }
    if (expireAfterAccess) {
      nodeSubtype.addField(newFieldOffset(className, "accessTime"))
          .addField(long.class, "accessTime", Modifier.PRIVATE, Modifier.VOLATILE)
          .addMethod(newGetter(Strength.STRONG, long.class, "accessTime"))
          .addMethod(newSetter(Strength.STRONG, long.class, "accessTime", true));
    }
    if (expireAfterWrite) {
      nodeSubtype.addField(newFieldOffset(className, "writeTime"))
          .addField(long.class, "writeTime", Modifier.PRIVATE, Modifier.VOLATILE)
          .addMethod(newGetter(Strength.STRONG, long.class, "writeTime"))
          .addMethod(newSetter(Strength.STRONG, long.class, "writeTime", true));
    }
    if (maximum || expireAfterAccess) {
      addFieldAndGetter(nodeSubtype, NODE, "previousInAccessOrder");
      addFieldAndGetter(nodeSubtype, NODE, "nextInAccessOrder");
    }
    if (expireAfterWrite) {
      addFieldAndGetter(nodeSubtype, NODE, "previousInWriteOrder");
      addFieldAndGetter(nodeSubtype, NODE, "nextInWriteOrder");
    }

    return nodeSubtype.addMethod(constructor.build()).build();
  }

  /** Creates a static field with an Unsafe address offset. */
  private FieldSpec newFieldOffset(String className, String varName) {
    String name = offsetName(varName);
    return FieldSpec
        .builder(long.class, name, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .initializer("$T.objectFieldOffset($T.class, $S)", UNSAFE_ACCESS,
            ClassName.bestGuess(className), varName).build();
  }

  private static String offsetName(String varName) {
    return CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, varName) + "_OFFSET";
  }

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

  private MethodSpec newGetter(Strength strength, Type varType, String varName) {
    String methodName = "get" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    MethodSpec.Builder getter = MethodSpec.methodBuilder(methodName)
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(varType);
    if ((varType == int.class) || (varType == long.class)) {
      getter.addAnnotation(Nonnegative.class);
    } else {
      getter.addAnnotation(Nullable.class);
    }
    if (strength == Strength.STRONG) {
      getter.addStatement("return $N", varName);
    } else {
      getter.addStatement("return $N.get()", varName);
    }
    return getter.build();
  }

  private MethodSpec newSetter(Strength strength, Type varType, String varName, boolean lazy) {
    String methodName = "set" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    Type annotation = (varType == int.class) || (varType == long.class)
        ? Nonnegative.class
        : Nullable.class;
    MethodSpec.Builder setter = MethodSpec.methodBuilder(methodName)
        .addParameter(ParameterSpec.builder(varType, varName).addAnnotation(annotation).build())
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC);
    if (strength == Strength.STRONG) {
      if (lazy) {
        setter.addStatement("$T.UNSAFE.putOrderedObject(this, $N, $N)",
            UNSAFE_ACCESS, offsetName(varName), varName);
      } else {
        setter.addStatement("this.$N = $N", varName, varName);
      }
    } else if (strength == Strength.WEAK) {
      if (lazy) {
        setter.addStatement("$T.UNSAFE.putOrderedObject(this, $N, new WeakReference<>($N))",
            UNSAFE_ACCESS, offsetName(varName), varName);
      } else {
        setter.addStatement("this.$N = new WeakReference<>($N)", varName, varName);
      }
    } else {
      if (lazy) {
        setter.addStatement("$T.UNSAFE.putOrderedObject(this, $N, new SoftReference<>($N))",
            UNSAFE_ACCESS, offsetName(varName), varName);
      } else {
        setter.addStatement("this.$N = new SoftReference<>($N)", varName, varName);
      }
    }
    return setter.build();
  }

  private void addConstructorAssignment(TypeSpec.Builder type, MethodSpec.Builder constructor,
      Strength strength, Type varType, String varName, Modifier modifier) {
    Modifier[] modifiers = { Modifier.PRIVATE, modifier };
    if (strength == Strength.STRONG) {
      type.addField(FieldSpec.builder(varType, varName, modifiers).build());
      constructor.addStatement("this.$N = $N", varName, varName);
    } else if (strength == Strength.WEAK) {
      type.addField(FieldSpec.builder(Types.parameterizedType(
          ClassName.get(WeakReference.class), varType), varName, modifiers).build());
      constructor.addStatement("this.$N = new WeakReference<>($N)", varName, varName);
    } else {
      type.addField(FieldSpec.builder(Types.parameterizedType(
          ClassName.get(SoftReference.class), varType), varName, modifiers).build());
      constructor.addStatement("this.$N = new SoftReference<>($N)", varName, varName);
    }
  }

  private void addFieldAndGetter(TypeSpec.Builder typeSpec, Type varType, String varName) {
    typeSpec.addField(varType, varName, Modifier.PRIVATE)
        .addMethod(newGetter(Strength.STRONG, varType, varName))
        .addMethod(newSetter(Strength.STRONG, varType, varName, false));
  }
}
