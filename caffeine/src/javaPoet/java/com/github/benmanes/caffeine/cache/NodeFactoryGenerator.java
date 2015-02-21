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
import static com.github.benmanes.caffeine.cache.Specifications.RETIRED_STRONG_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.RETIRED_WEAK_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.kRefQueueType;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keyRefSpec;
import static com.github.benmanes.caffeine.cache.Specifications.keySpec;
import static com.github.benmanes.caffeine.cache.Specifications.lookupKeyType;
import static com.github.benmanes.caffeine.cache.Specifications.rawReferenceKeyType;
import static com.github.benmanes.caffeine.cache.Specifications.referenceKeyType;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.valueRefQueueSpec;
import static com.github.benmanes.caffeine.cache.Specifications.valueSpec;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Specifications.Strength;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
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

  final Path directory;

  TypeSpec.Builder nodeFactory;

  public NodeFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
  }

  void generate() throws IOException {
    nodeFactory = newNodeFactoryBuilder();
    generatedNodes();
    writeJavaFile();
  }

  private void writeJavaFile() throws IOException {
    JavaFile.builder(getClass().getPackage().getName(), nodeFactory.build())
        .addFileComment("Copyright $L Ben Manes. All Rights Reserved.", Year.now())
        .indent("  ")
        .build()
        .writeTo(directory);
  }

  private TypeSpec.Builder newNodeFactoryBuilder() {
    nodeFactory = TypeSpec.enumBuilder("NodeFactory");
    addClassJavaDoc();
    addNodeStateStatics();
    addKeyMethods();
    addGetFactoryMethods();
    return nodeFactory;
  }

  private void addClassJavaDoc() {
    nodeFactory.addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for cache nodes optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n");
  }

  private void addNodeStateStatics() {
    Modifier[] modifiers = { Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL };
    nodeFactory.addType(TypeSpec.enumBuilder("State")
        .addModifiers(Modifier.PRIVATE)
        .addEnumConstant("RETIRED")
        .addEnumConstant("DEAD")
        .build());

    nodeFactory.addField(FieldSpec.builder(Object.class, RETIRED_STRONG_KEY, modifiers)
        .initializer("State.RETIRED")
        .build());
    nodeFactory.addField(FieldSpec.builder(Object.class, DEAD_STRONG_KEY, modifiers)
        .initializer("State.DEAD")
        .build());

    nodeFactory.addField(FieldSpec.builder(rawReferenceKeyType, RETIRED_WEAK_KEY, modifiers)
        .initializer("new $T($N, null)", rawReferenceKeyType, RETIRED_STRONG_KEY)
        .build());
    nodeFactory.addField(FieldSpec.builder(rawReferenceKeyType, DEAD_WEAK_KEY, modifiers)
        .initializer("new $T($N, null)", rawReferenceKeyType, DEAD_STRONG_KEY)
        .build());
  }

  private void addKeyMethods() {
    nodeFactory.addMethod(newNodeByKeyAbstractMethod())
        .addMethod(newNodeByKeyRefAbstractMethod())
        .addMethod(newLookupKeyMethod())
        .addMethod(newReferenceKeyMethod());
  }

  private MethodSpec newNodeByKeyAbstractMethod() {
    return newNodeByKey().addModifiers(Modifier.ABSTRACT)
        .addJavadoc("Returns a node optimized for the specified features.\n").build();
  }

  private MethodSpec newNodeByKeyRefAbstractMethod() {
    return newNodeByKeyRef().addModifiers(Modifier.ABSTRACT)
        .addJavadoc("Returns a node optimized for the specified features.\n").build();
  }

  private MethodSpec newLookupKeyMethod() {
    return MethodSpec.methodBuilder("newLookupKey")
        .addJavadoc("Returns a key suitable for looking up an entry in the cache. If the cache "
            + "holds keys strongly\nthen the key is returned. If the cache holds keys weakly "
            + "then a {@link $T}\nholding the key argument is returned.\n", lookupKeyType)
        .addTypeVariable(kTypeVar)
        .addParameter(ParameterSpec.builder(kTypeVar, "key")
            .addAnnotation(Nonnull.class).build())
        .returns(Object.class).addAnnotation(Nonnull.class)
        .addStatement("return key")
        .build();
  }

  private MethodSpec newReferenceKeyMethod() {
    return MethodSpec.methodBuilder("newReferenceKey")
        .addJavadoc("Returns a key suitable for inserting into the cache. If the cache holds"
            + " keys strongly then\nthe key is returned. If the cache holds keys weakly "
            + "then a {@link $T}\nholding the key argument is returned.\n", referenceKeyType)
        .addTypeVariable(kTypeVar)
        .addParameter(ParameterSpec.builder(kTypeVar, "key")
            .addAnnotation(Nonnull.class).build())
        .addParameter(ParameterSpec.builder(kRefQueueType, "referenceQueue")
            .addAnnotation(Nonnull.class).build())
        .returns(Object.class).addAnnotation(Nonnull.class)
        .addStatement("return $L", "key")
        .build();
  }

  private void addGetFactoryMethods() {
    List<String> params = ImmutableList.of("strongKeys", "weakKeys", "strongValues", "weakValues",
        "softValues", "expiresAfterAccess", "expiresAfterWrite", "refreshAfterWrite",
        "maximumSize", "weighed");
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

    getFactory
        .beginControlFlow("for (NodeFactory factory : values())")
            .beginControlFlow(condition.toString())
                .addStatement("return factory")
            .endControlFlow()
        .endControlFlow()
        .addStatement("throw new $T()", IllegalArgumentException.class)
        .addModifiers(Modifier.STATIC)
        .build();
    nodeFactory.addMethod(getFactory.build());
  }

  private void generatedNodes() throws IOException {
    Set<String> seen = new HashSet<>();
    for (List<Object> combination : combinations()) {
      addNodeSpec(seen,
          (Strength) combination.get(0),
          (Strength) combination.get(1),
          (boolean) combination.get(2),
          (boolean) combination.get(3),
          (boolean) combination.get(4),
          (boolean) combination.get(5),
          (boolean) combination.get(6));
    }
  }

  private void addNodeSpec(Set<String> seen, Strength keyStrength, Strength valueStrength,
      boolean expireAfterAccess, boolean expireAfterWrite, boolean refreshAfterWrite,
      boolean maximum, boolean weighed) throws IOException {
    String enumName = makeEnumName(keyStrength, valueStrength,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, maximum, weighed);
    String className = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, enumName);
    if (!seen.add(className)) {
      // skip duplicates
      return;
    }

    addEnumConstant(className, enumName, keyStrength, valueStrength,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, maximum, weighed);

    NodeGenerator nodeGenerator = new NodeGenerator(className, keyStrength, valueStrength,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, maximum, weighed);
    TypeSpec.Builder nodeSubType = nodeGenerator.createNodeType();
    nodeFactory.addType(nodeSubType.build());
  }

  private void addEnumConstant(String className, String enumName, Strength keyStrength,
      Strength valueStrength, boolean expireAfterAccess, boolean expireAfterWrite,
      boolean refreshAfterWrite, boolean maximum, boolean weighed) {
    String statementWithKey = makeFactoryStatementKey(keyStrength, valueStrength,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, weighed);
    String statementWithKeyRef = makeFactoryStatementKeyRef(valueStrength,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, weighed);
    TypeSpec.Builder typeSpec = TypeSpec.anonymousClassBuilder("")
        .addMethod(newNodeByKey().addAnnotation(Override.class)
            .addStatement(statementWithKey, className).build())
        .addMethod(newNodeByKeyRef().addAnnotation(Override.class)
            .addStatement(statementWithKeyRef, className).build())
        .addMethod(isMethod("strongKeys", keyStrength == Strength.STRONG))
        .addMethod(isMethod("weakKeys", keyStrength == Strength.WEAK))
        .addMethod(isMethod("strongValues", valueStrength == Strength.STRONG))
        .addMethod(isMethod("weakValues", valueStrength == Strength.WEAK))
        .addMethod(isMethod("softValues", valueStrength == Strength.SOFT))
        .addMethod(isMethod("expiresAfterAccess", expireAfterAccess))
        .addMethod(isMethod("expiresAfterWrite", expireAfterWrite))
        .addMethod(isMethod("refreshAfterWrite", refreshAfterWrite))
        .addMethod(isMethod("maximumSize", maximum))
        .addMethod(isMethod("weighed", weighed));
    if (keyStrength == Strength.WEAK) {
      typeSpec.addMethod(makeNewLookupKey());
      typeSpec.addMethod(makeReferenceKey());
    }
    nodeFactory.addEnumConstant(enumName, typeSpec.build());
  }

  private String makeFactoryStatementKey(Strength keyStrength, Strength valueStrength,
      boolean expireAfterAccess, boolean expireAfterWrite, boolean refreshAfterWrite,
      boolean weighed) {
    StringBuilder statement = new StringBuilder("return new $N<>(key, keyReferenceQueue");
    return completeFactoryStatement(statement, valueStrength,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, weighed);
  }

  private String makeFactoryStatementKeyRef(Strength valueStrength,
      boolean expireAfterAccess, boolean expireAfterWrite, boolean refreshAfterWrite,
      boolean weighed) {
    StringBuilder statement = new StringBuilder("return new $N<>(keyReference");
    return completeFactoryStatement(statement, valueStrength,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, weighed);
  }

  private String completeFactoryStatement(StringBuilder statement, Strength valueStrength,
      boolean expireAfterAccess, boolean expireAfterWrite, boolean refreshAfterWrite,
      boolean weighed) {
    statement.append(", value");
    if (valueStrength != Strength.STRONG) {
      statement.append(", valueReferenceQueue");
    }
    if (weighed) {
      statement.append(", weight");
    }
    if (expireAfterAccess || expireAfterWrite || refreshAfterWrite) {
      statement.append(", now");
    }
    statement.append(")");
    return statement.toString();
  }

  private MethodSpec isMethod(String varName, boolean value) {
    return MethodSpec.methodBuilder(varName)
        .addStatement("return " + value)
        .returns(boolean.class).build();
  }

  private MethodSpec makeNewLookupKey() {
    return MethodSpec.methodBuilder("newLookupKey")
        .addTypeVariable(kTypeVar)
        .addParameter(ParameterSpec.builder(kTypeVar, "key")
            .addAnnotation(Nonnull.class).build())
        .returns(Object.class).addAnnotation(Nonnull.class)
        .addAnnotation(Override.class)
        .addStatement("return new $T(key)", lookupKeyType)
        .build();
  }

  private MethodSpec makeReferenceKey() {
    return MethodSpec.methodBuilder("newReferenceKey")
        .addTypeVariable(kTypeVar)
        .addParameter(ParameterSpec.builder(kTypeVar, "key")
            .addAnnotation(Nonnull.class).build())
        .addParameter(ParameterSpec.builder(kRefQueueType, "referenceQueue")
            .addAnnotation(Nonnull.class).build())
        .returns(Object.class).addAnnotation(Nonnull.class)
        .addAnnotation(Override.class)
        .addStatement("return new $T($L, $L)", referenceKeyType, "key", "referenceQueue")
        .build();
  }

  private String makeEnumName(Strength keyStrength, Strength valueStrength,
      boolean expireAfterAccess, boolean expireAfterWrite, boolean refreshAfterWrite,
      boolean maximum, boolean weighed) {
    StringBuilder name = new StringBuilder(keyStrength + "_KEYS_" + valueStrength + "_VALUES");
    if (expireAfterAccess) {
      name.append("_EXPIRE_ACCESS");
    }
    if (expireAfterWrite) {
      name.append("_EXPIRE_WRITE");
    }
    if (refreshAfterWrite) {
      name.append("_REFRESH_WRITE");
    }
    if (maximum) {
      name.append("_MAXIMUM");
      if (weighed) {
        name.append("_WEIGHT");
      } else {
        name.append("_SIZE");
      }
    } else if (weighed) {
      // FIXME: Used for async caches
      name.append("_WEIGHT");
    }
    return name.toString();
  }

  private Set<List<Object>> combinations() {
    Set<Strength> keyStrengths = ImmutableSet.of(Strength.STRONG, Strength.WEAK);
    Set<Strength> valueStrengths = ImmutableSet.of(Strength.STRONG, Strength.WEAK, Strength.SOFT);
    Set<Boolean> expireAfterAccess = ImmutableSet.of(false, true);
    Set<Boolean> expireAfterWrite = ImmutableSet.of(false, true);
    Set<Boolean> refreshAfterWrite = ImmutableSet.of(false, true);
    Set<Boolean> maximumSize = ImmutableSet.of(false, true);
    Set<Boolean> weighed = ImmutableSet.of(false, true);

    @SuppressWarnings("unchecked")
    Set<List<Object>> combinations = Sets.cartesianProduct(keyStrengths, valueStrengths,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, maximumSize, weighed);
    return combinations;
  }

  private MethodSpec.Builder newNodeByKey() {
    return completeNewNode(MethodSpec.methodBuilder("newNode")
        .addParameter(keySpec)
        .addParameter(keyRefQueueSpec));
  }

  private MethodSpec.Builder newNodeByKeyRef() {
    return completeNewNode(MethodSpec.methodBuilder("newNode").addParameter(keyRefSpec));
  }

  private MethodSpec.Builder completeNewNode(MethodSpec.Builder method) {
    return method
        .addAnnotation(Nonnull.class)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(valueSpec)
        .addParameter(valueRefQueueSpec)
        .addParameter(ParameterSpec.builder(int.class, "weight")
            .addAnnotation(Nonnegative.class).build())
        .addParameter(ParameterSpec.builder(long.class, "now")
            .addAnnotation(Nonnegative.class).build())
        .returns(Specifications.NODE);
  }

  public static void main(String[] args) throws IOException {
    new NodeFactoryGenerator(Paths.get(args[0])).generate();
  }
}
