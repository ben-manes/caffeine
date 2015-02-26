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
import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import javax.lang.model.element.Modifier;

import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
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
  final Path directory;
  final Set<String> seen;

  TypeSpec.Builder nodeFactory;

  public NodeFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
    this.seen = new HashSet<>();
  }

  void generate() throws IOException {
    AnnotationSpec suppressed = AnnotationSpec.builder(SuppressWarnings.class)
        .addMember("value", "$S", "unchecked")
        .build();
    nodeFactory = TypeSpec.enumBuilder("NodeFactory").addAnnotation(suppressed);
    addClassJavaDoc();
    addNodeStateStatics();
    addKeyMethods();
    generatedNodes();
    addGetFactoryMethods();
    writeJavaFile();
  }

  private void writeJavaFile() throws IOException {
    JavaFile.builder(getClass().getPackage().getName(), nodeFactory.build())
        .addFileComment("Copyright $L Ben Manes. All Rights Reserved.", Year.now())
        .indent("  ")
        .build()
        .writeTo(directory);
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
        .addParameter(kTypeVar, "key")
        .addStatement("return key")
        .returns(Object.class)
        .build();
  }

  private MethodSpec newReferenceKeyMethod() {
    return MethodSpec.methodBuilder("newReferenceKey")
        .addJavadoc("Returns a key suitable for inserting into the cache. If the cache holds"
            + " keys strongly then\nthe key is returned. If the cache holds keys weakly "
            + "then a {@link $T}\nholding the key argument is returned.\n", referenceKeyType)
        .addTypeVariable(kTypeVar)
        .addParameter(kTypeVar, "key")
        .addParameter(kRefQueueType, "referenceQueue")
        .returns(Object.class)
        .addStatement("return $L", "key")
        .build();
  }

  private void addGetFactoryMethods() {
    MethodSpec.Builder getFactory = MethodSpec.methodBuilder("getFactory")
        .addJavadoc("Returns a factory optimized for the specified features.\n")
        .returns(ClassName.bestGuess("NodeFactory"));

    List<String> params = ImmutableList.of("strongKeys", "weakKeys", "strongValues", "weakValues",
        "softValues", "expiresAfterAccess", "expiresAfterWrite", "refreshAfterWrite",
        "maximumSize", "weighed");
    for (String param : params) {
      getFactory.addParameter(boolean.class, param);
    }

    for (String param : ImmutableList.of("weakValues", "softValues")) {
      String feature = CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, param);
      String property = feature.replace('_', ' ').toLowerCase();
      nodeFactory.addMethod(MethodSpec.methodBuilder(param)
          .addJavadoc("Returns whether this factory supports the " + property + " feature.\n")
          .addStatement("return name().contains($S)", feature)
          .returns(boolean.class)
          .build());
    }

    OptionalInt bufferSize = seen.stream().mapToInt(s -> s.length()).max();
    Preconditions.checkState(bufferSize.isPresent(), "Must generate all cache types first");
    getFactory
        .addCode(NodeSelectorCode.get(bufferSize.getAsInt()))
        .addModifiers(Modifier.STATIC)
        .build();
    nodeFactory.addMethod(getFactory.build());
  }

  private void generatedNodes() throws IOException {
    Feature[] featureByIndex = new Feature[] { null, null,
        Feature.EXPIRE_ACCESS, Feature.EXPIRE_WRITE, Feature.REFRESH_WRITE,
        Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT };

    for (List<Object> combination : combinations()) {
      Set<Feature> features = new LinkedHashSet<>();

      features.add((Feature) combination.get(0));
      features.add((Feature) combination.get(1));
      for (int i = 2; i < combination.size(); i++) {
        if ((Boolean) combination.get(i)) {
          features.add(featureByIndex[i]);
        }
      }
      if (features.contains(Feature.MAXIMUM_WEIGHT)) {
        features.remove(Feature.MAXIMUM_SIZE);
      }

      addNodeSpec(features);
    }
  }

  private void addNodeSpec(Set<Feature> features) throws IOException {
    String className = Feature.makeClassName(features);
    if (!seen.add(className)) {
      // skip duplicates
      return;
    }

    TypeName superClass;
    Set<Feature> parentFeatures;
    Set<Feature> generateFeatures;
    if (features.size() == 2) {
      parentFeatures = ImmutableSet.of();
      generateFeatures = features;
      superClass = TypeName.OBJECT;
    } else {
      parentFeatures = ImmutableSet.copyOf(Iterables.limit(features, features.size() - 1));
      generateFeatures = ImmutableSet.of(Iterables.getLast(features));
      superClass = ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME,
          Feature.makeClassName(parentFeatures)), kTypeVar, vTypeVar);
    }

    NodeGenerator nodeGenerator = new NodeGenerator(
        superClass, className, parentFeatures, generateFeatures);
    TypeSpec.Builder nodeSubType = nodeGenerator.createNodeType();
    nodeFactory.addType(nodeSubType.build());
    addEnumConstant(className, features);
  }

  private void addEnumConstant(String className, Set<Feature> features) {
    String statementWithKey = makeFactoryStatementKey();
    String statementWithKeyRef = makeFactoryStatementKeyRef();
    TypeSpec.Builder typeSpec = TypeSpec.anonymousClassBuilder("")
        .addMethod(newNodeByKey()
            .addStatement(statementWithKey, className).build())
        .addMethod(newNodeByKeyRef()
            .addStatement(statementWithKeyRef, className).build());
    if (features.contains(Feature.WEAK_KEYS)) {
      typeSpec.addMethod(makeNewLookupKey());
      typeSpec.addMethod(makeReferenceKey());
    }
    nodeFactory.addEnumConstant(Feature.makeEnumName(features), typeSpec.build());
  }

  private String makeFactoryStatementKey() {
    return "return new $N<>(key, keyReferenceQueue, value, valueReferenceQueue, weight, now)";
  }

  private String makeFactoryStatementKeyRef() {
    return "return new $N<>(keyReference, value, valueReferenceQueue, weight, now)";
  }

  private MethodSpec makeNewLookupKey() {
    return MethodSpec.methodBuilder("newLookupKey")
        .addTypeVariable(kTypeVar)
        .addParameter(kTypeVar, "key")
        .addStatement("return new $T(key)", lookupKeyType)
        .returns(Object.class)
        .build();
  }

  private MethodSpec makeReferenceKey() {
    return MethodSpec.methodBuilder("newReferenceKey")
        .addTypeVariable(kTypeVar)
        .addParameter(kTypeVar, "key")
        .addParameter(kRefQueueType, "referenceQueue")
        .addStatement("return new $T($L, $L)", referenceKeyType, "key", "referenceQueue")
        .returns(Object.class)
        .build();
  }

  private Set<List<Object>> combinations() {
    Set<Feature> keyStrengths = ImmutableSet.of(Feature.STRONG_KEYS, Feature.WEAK_KEYS);
    Set<Feature> valueStrengths = ImmutableSet.of(
        Feature.STRONG_VALUES, Feature.WEAK_VALUES, Feature.SOFT_VALUES);
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
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(valueSpec)
        .addParameter(valueRefQueueSpec)
        .addParameter(int.class, "weight")
        .addParameter(long.class, "now")
        .returns(Specifications.NODE);
  }

  public static void main(String[] args) throws IOException {
    new NodeFactoryGenerator(Paths.get(args[0])).generate();
  }
}
