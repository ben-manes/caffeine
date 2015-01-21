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
package com.github.benmanes.playground;

import java.io.IOException;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.lang.model.element.Modifier;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.Types;

/**
 * Experiments with JavaPoet to generate the 96 different cache entry types. If successful, this
 * code will be moved into its own sourceSet for build time generation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeGenerator {
  enum Strength { STRONG, WEAK, SOFT }

  final TypeVariable<?> kTypeVar = Types.typeVariable("K");
  final TypeVariable<?> vTypeVar = Types.typeVariable("V");
  final Type kType = ClassName.get(getClass().getPackage().getName(), "K");
  final Type vType = ClassName.get(getClass().getPackage().getName(), "V");

  void generate() throws IOException {
    TypeSpec.Builder nodeFactoryBuilder = newNodeFactoryBuilder();
    generatedNodes(nodeFactoryBuilder);
    makeJavaFile(nodeFactoryBuilder).emit(System.out);
  }

  void generatedNodes(TypeSpec.Builder nodeFactoryBuilder) {
    Set<String> seen = new HashSet<>();
    for (List<Object> combination : combinations()) {
      addNodeSpec(nodeFactoryBuilder, seen,
          (Strength) combination.get(0),
          (Strength) combination.get(1),
          (boolean) combination.get(2),
          (boolean) combination.get(3),
          (boolean) combination.get(4),
          (boolean) combination.get(5));
    }
  }

  void addNodeSpec(TypeSpec.Builder nodeFactoryBuilder, Set<String> seen, Strength keyStrength,
      Strength valueStrength, boolean expireAfterAccess, boolean expireAfterWrite,
      boolean maximum, boolean weighed) {
    StringBuilder name = new StringBuilder(keyStrength + "_KEYS_" + valueStrength + "_VALUES");
    if (expireAfterAccess) {
      name.append("_EXPIRE_ACCESS");
    }
    if (expireAfterWrite) {
      name.append("_EXPIRE_WRITE");
    }
    if (maximum) {
      name.append("_MAXIMUM");
      if (weighed) {
        name.append("_WEIGHT");
      } else {
        name.append("_SIZE");
      }
    }

    String className = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, name.toString());
    if (!seen.add(className)) {
      // skip duplicates
      return;
    }

    MethodSpec newNodeSpec = newNode()
        .addAnnotation(Override.class)
        .addCode("return new " + className + "<K, V>(key, value);\n")
        .build();
    TypeSpec typeSpec = TypeSpec.anonymousClassBuilder("")
        .addMethod(newNodeSpec)
        .build();
    nodeFactoryBuilder.addEnumConstant(name.toString(), typeSpec);

    nodeFactoryBuilder.addType(TypeSpec.classBuilder(className)
        .addSuperinterface(Types.parameterizedType(Node.class, kType, vType))
        .addModifiers(Modifier.STATIC, Modifier.FINAL)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .build());
  }

  Set<List<Object>> combinations() {
    Set<Strength> keyStrengths = ImmutableSet.of(Strength.STRONG, Strength.WEAK);
    Set<Strength> valueStrengths = ImmutableSet.of(Strength.STRONG, Strength.WEAK, Strength.SOFT);
    Set<Boolean> expireAfterAccess = ImmutableSet.of(false, true);
    Set<Boolean> expireAfterWrite = ImmutableSet.of(false, true);
    Set<Boolean> maximumSize = ImmutableSet.of(false, true);
    Set<Boolean> weighed = ImmutableSet.of(false, true);

    @SuppressWarnings("unchecked")
    Set<List<Object>> combinations = Sets.cartesianProduct(keyStrengths, valueStrengths,
        expireAfterAccess, expireAfterWrite, maximumSize, weighed);
    return combinations;
  }

  JavaFile makeJavaFile(TypeSpec.Builder nodeFactoryBuilder) {
    return JavaFile.builder(getClass().getPackage().getName(), nodeFactoryBuilder.build())
        .addFileComment("Copyright 2015 Ben Manes. All Rights Reserved.")
        .build();
  }

  TypeSpec.Builder newNodeFactoryBuilder() {
    return TypeSpec.enumBuilder("NodeFactory")
        .addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for cache nodes optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n")
        .addMethod(newNode()
        /* .addModifiers(Modifier.ABSTRACT) */
        .addStatement("throw new UnsupportedOperationException()")
        .build());
  }

  MethodSpec.Builder newNode() {
    ParameterSpec keySpec = ParameterSpec.builder(kType, "key")
        .addAnnotation(Nonnull.class).build();
    ParameterSpec valueSpec = ParameterSpec.builder(vType, "value")
        .addAnnotation(Nonnull.class).build();
    return MethodSpec.methodBuilder("newNode")
        .addAnnotation(Nonnull.class)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(keySpec)
        .addParameter(valueSpec)
        .returns(Types.parameterizedType(Node.class, kType, vType));
  }

  public static void main(String[] args) throws IOException {
    new NodeGenerator().generate();
  }
}
