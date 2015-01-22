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

import static com.github.benmanes.caffeine.cache.NodeSpec.kTypeVar;
import static com.github.benmanes.caffeine.cache.NodeSpec.keySpec;
import static com.github.benmanes.caffeine.cache.NodeSpec.vTypeVar;
import static com.github.benmanes.caffeine.cache.NodeSpec.valueSpec;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.NodeSpec.Strength;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

/**
 * Generates the cache entry factory and specialized types. These entries are optimized for the
 * configuration to minimize memory use. An entry may have any of the following properties:
 * <ul>
 *   <li>strong or weak keys
 *   <li>strong, weak, or soft values
 *   <li>access timestamp
 *   <li>write timestamp
 *   <li>weight
 * </ul>
 * <p>
 * If the cache has either a maximum size or expires after access, then the entry will also contain
 * prev/next references on a access ordered queue. If the cache expires after write, then the entry
 * will also contain prev/next on a write ordered queue.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeFactoryGenerator {
  static final String PACKAGE_NAME = NodeFactoryGenerator.class.getPackage().getName();

  void generate(Appendable writer) throws IOException {
    TypeSpec.Builder nodeFactoryBuilder = newNodeFactoryBuilder();
    generatedNodes(nodeFactoryBuilder);
    makeJavaFile(nodeFactoryBuilder).emit(writer);
  }

  private JavaFile makeJavaFile(TypeSpec.Builder nodeFactoryBuilder) {
    return JavaFile.builder(getClass().getPackage().getName(), nodeFactoryBuilder.build())
        .addFileComment("Copyright 2015 Ben Manes. All Rights Reserved.")
        .build();
  }

  private TypeSpec.Builder newNodeFactoryBuilder() {
    TypeSpec.Builder nodeFactory = TypeSpec.enumBuilder("NodeFactory")
        .addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for cache nodes optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n")
        .addMethod(newNode().addModifiers(Modifier.ABSTRACT)
            .addJavadoc("Returns a node optimized for the specified features.\n").build());

    List<String> params = ImmutableList.of("strongKeys", "weakKeys", "strongValues", "weakValues",
        "softValues", "expireAfterAccess", "expireAfterWrite", "maximumSize", "weighed");
    MethodSpec.Builder getFactory = MethodSpec.methodBuilder("getFactory")
        .addJavadoc("Returns a factory optimized for the specified features.\n")
        .returns(ClassName.bestGuess("NodeFactory")).addAnnotation(Nonnull.class);
    StringBuilder condition = new StringBuilder("if (");
    for (String param : params) {
      String property = CaseFormat.UPPER_CAMEL.to(
          CaseFormat.LOWER_UNDERSCORE, param).replace('_', ' ');
      nodeFactory.addMethod(MethodSpec.methodBuilder(param)
          .addJavadoc("Returns whether this factory supports the " + property + " feature.\n")
          .addModifiers(Modifier.ABSTRACT)
          .returns(boolean.class).build());
      getFactory.addParameter(boolean.class, param);
      condition.append("(").append(param).append(" == factory.").append(param).append("())");
      condition.append("\n    && ");
    }
    condition.delete(condition.length() - 8, condition.length());
    condition.append(")");

    getFactory.beginControlFlow("for (NodeFactory factory : values())")
        .beginControlFlow(condition.toString())
        .addStatement("return factory")
        .endControlFlow()
        .endControlFlow()
        .addStatement("throw new $T()", IllegalArgumentException.class)
        .addModifiers(Modifier.STATIC)
        .build();
    return nodeFactory.addMethod(getFactory.build());
  }

  private void generatedNodes(TypeSpec.Builder nodeFactoryBuilder) {
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

  private void addNodeSpec(TypeSpec.Builder nodeFactoryBuilder, Set<String> seen, Strength keyStrength,
      Strength valueStrength, boolean expireAfterAccess, boolean expireAfterWrite,
      boolean maximum, boolean weighed) {
    String enumName = makeEnumName(keyStrength, valueStrength,
        expireAfterAccess, expireAfterWrite, maximum, weighed);
    String className = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, enumName);
    if (!seen.add(className)) {
      // skip duplicates
      return;
    }

    addEnumConstant(className, enumName, nodeFactoryBuilder, keyStrength, valueStrength,
        expireAfterAccess, expireAfterWrite, maximum, weighed);
    TypeSpec nodeSubType = new NodeImplGenerator().createNodeType(className, enumName, keyStrength,
        valueStrength, expireAfterAccess, expireAfterWrite, maximum, weighed);
    nodeFactoryBuilder.addType(nodeSubType).build();
  }

  private void addEnumConstant(String className, String enumName, TypeSpec.Builder nodeFactoryBuilder,
      Strength keyStrength, Strength valueStrength, boolean expireAfterAccess,
      boolean expireAfterWrite, boolean maximum, boolean weighed) {
    MethodSpec newNodeSpec = newNode()
        .addAnnotation(Override.class)
        .addCode("return new $N<>(key, value);\n", className)
        .build();
    TypeSpec typeSpec = TypeSpec.anonymousClassBuilder("")
        .addMethod(newNodeSpec)
        .addMethod(isMethod("strongKeys", keyStrength == Strength.STRONG))
        .addMethod(isMethod("weakKeys", keyStrength == Strength.WEAK))
        .addMethod(isMethod("strongValues", valueStrength == Strength.STRONG))
        .addMethod(isMethod("weakValues", valueStrength == Strength.WEAK))
        .addMethod(isMethod("softValues", valueStrength == Strength.SOFT))
        .addMethod(isMethod("expireAfterAccess", expireAfterAccess))
        .addMethod(isMethod("expireAfterWrite", expireAfterWrite))
        .addMethod(isMethod("maximumSize", maximum))
        .addMethod(isMethod("weighed", weighed))
        .build();
    nodeFactoryBuilder.addEnumConstant(enumName, typeSpec);
  }

  private MethodSpec isMethod(String varName, boolean value) {
    return MethodSpec.methodBuilder(varName)
        .addStatement("return " + value)
        .returns(boolean.class).build();
  }

  private String makeEnumName(Strength keyStrength, Strength valueStrength,
      boolean expireAfterAccess, boolean expireAfterWrite, boolean maximum, boolean weighed) {
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
    return name.toString();
  }

  private Set<List<Object>> combinations() {
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

  private MethodSpec.Builder newNode() {
    return MethodSpec.methodBuilder("newNode")
        .addAnnotation(Nonnull.class)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(keySpec)
        .addParameter(valueSpec)
        .returns(NodeSpec.NODE);
  }

  public static void main(String[] args) throws IOException {
    if (args.length == 0) {
      new NodeFactoryGenerator().generate(System.out);
      return;
    }
    String directory = args[0] + PACKAGE_NAME.replace('.', '/');
    new File(directory).mkdirs();
    Path path = Paths.get(directory, "/NodeFactory.java");
    try (BufferedWriter writer = Files.newBufferedWriter(path)) {
      new NodeFactoryGenerator().generate(writer);
    }
  }
}
