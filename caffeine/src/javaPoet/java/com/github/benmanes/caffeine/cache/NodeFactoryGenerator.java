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

import static com.github.benmanes.caffeine.cache.Specifications.BUILDER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.DEAD_STRONG_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.DEAD_WEAK_KEY;
import static com.github.benmanes.caffeine.cache.Specifications.NODE;
import static com.github.benmanes.caffeine.cache.Specifications.NODE_FACTORY;
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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.node.AddConstructors;
import com.github.benmanes.caffeine.cache.node.AddDeques;
import com.github.benmanes.caffeine.cache.node.AddExpiration;
import com.github.benmanes.caffeine.cache.node.AddFactoryMethods;
import com.github.benmanes.caffeine.cache.node.AddHealth;
import com.github.benmanes.caffeine.cache.node.AddKey;
import com.github.benmanes.caffeine.cache.node.AddMaximum;
import com.github.benmanes.caffeine.cache.node.AddSubtype;
import com.github.benmanes.caffeine.cache.node.AddValue;
import com.github.benmanes.caffeine.cache.node.Finalize;
import com.github.benmanes.caffeine.cache.node.NodeContext;
import com.github.benmanes.caffeine.cache.node.NodeRule;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
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
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class NodeFactoryGenerator {
  static final FieldSpec LOOKUP = FieldSpec.builder(MethodHandles.Lookup.class, "LOOKUP")
      .initializer("$T.lookup()", MethodHandles.class)
      .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
      .build();
  static final FieldSpec FACTORY = FieldSpec.builder(MethodType.class, "FACTORY")
      .initializer("$T.methodType($T.class)", MethodType.class, void.class)
      .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
      .build();

  final List<NodeRule> rules = ImmutableList.of(new AddSubtype(), new AddConstructors(),
      new AddKey(), new AddValue(), new AddMaximum(), new AddExpiration(), new AddDeques(),
      new AddFactoryMethods(),  new AddHealth(), new Finalize());
  final Feature[] featureByIndex = { null, null, Feature.EXPIRE_ACCESS, Feature.EXPIRE_WRITE,
      Feature.REFRESH_WRITE, Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT };
  final ZoneId timeZone = ZoneId.of("America/Los_Angeles");
  final Path directory;

  TypeSpec.Builder nodeFactory;

  private final List<TypeSpec> nodeTypes;

  @SuppressWarnings("NullAway.Init")
  public NodeFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
    this.nodeTypes = new ArrayList<>();
  }

  void generate() throws IOException {
    nodeFactory = TypeSpec.interfaceBuilder("NodeFactory")
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar);
    addClassJavaDoc();
    addConstants();
    addKeyMethods();
    generatedNodes();
    addNewFactoryMethods();
    writeJavaFile();
    reformat();
  }

  private void writeJavaFile() throws IOException {
    String header = Resources.toString(Resources.getResource("license.txt"), UTF_8).trim();
    JavaFile.builder(getClass().getPackage().getName(), nodeFactory.build())
        .addFileComment(header, Year.now(timeZone))
        .indent("  ")
        .build()
        .writeTo(directory);

    for (TypeSpec node : nodeTypes) {
      JavaFile.builder(getClass().getPackage().getName(), node)
          .addFileComment(header, Year.now(timeZone))
          .indent("  ")
          .build()
          .writeTo(directory);
    }
  }

  private void reformat() throws IOException {
    try (Stream<Path> stream = Files.walk(directory)) {
      List<Path> files = stream
          .filter(path -> path.toString().endsWith(".java"))
          .collect(toList());
      Formatter formatter = new Formatter();
      for (Path file : files) {
        String source = Files.readString(file);
        String formatted = formatter.formatSourceAndFixImports(source);
        Files.writeString(file, formatted);
      }
    } catch (FormatterException e) {
      throw new RuntimeException(e);
    }
  }

  private void addClassJavaDoc() {
    nodeFactory.addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for cache nodes optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n");
  }

  private void addConstants() {
    List<String> constants = ImmutableList.of("key", "value", "accessTime", "writeTime");
    for (String constant : constants) {
      String name = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, constant);
      nodeFactory.addField(FieldSpec.builder(String.class, name)
          .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
          .initializer("$S", constant)
          .build());
    }

    Modifier[] modifiers = { Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL };
    nodeFactory.addField(FieldSpec.builder(Object.class, RETIRED_STRONG_KEY, modifiers)
        .initializer("new Object()")
        .build());
    nodeFactory.addField(FieldSpec.builder(Object.class, DEAD_STRONG_KEY, modifiers)
        .initializer("new Object()")
        .build());
    nodeFactory.addField(FieldSpec.builder(rawReferenceKeyType, RETIRED_WEAK_KEY, modifiers)
        .initializer("new $T(null, null)", rawReferenceKeyType)
        .build());
    nodeFactory.addField(FieldSpec.builder(rawReferenceKeyType, DEAD_WEAK_KEY, modifiers)
        .initializer("new $T(null, null)", rawReferenceKeyType)
        .build());

    nodeFactory.addField(FACTORY);
    nodeFactory.addField(LOOKUP);
  }

  private void addKeyMethods() {
    nodeFactory
        .addMethod(newNodeMethod(keySpec, keyRefQueueSpec))
        .addMethod(newNodeMethod(keyRefSpec))
        .addMethod(newReferenceKeyMethod())
        .addMethod(newLookupKeyMethod());
  }

  private MethodSpec newNodeMethod(ParameterSpec... keyParams) {
    return MethodSpec.methodBuilder("newNode")
        .addJavadoc("Returns a node optimized for the specified features.\n")
        .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
        .addParameters(ImmutableList.copyOf(keyParams))
        .addParameter(valueSpec)
        .addParameter(valueRefQueueSpec)
        .addParameter(int.class, "weight")
        .addParameter(long.class, "now")
        .returns(NODE)
        .build();
  }

  private MethodSpec newLookupKeyMethod() {
    return MethodSpec.methodBuilder("newLookupKey")
        .addJavadoc("Returns a key suitable for looking up an entry in the cache. If the cache "
            + "holds keys strongly\nthen the key is returned. If the cache holds keys weakly "
            + "then a {@link $T}\nholding the key argument is returned.\n", lookupKeyType)
        .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
        .addParameter(Object.class, "key")
        .addStatement("return key")
        .returns(Object.class)
        .build();
  }

  private MethodSpec newReferenceKeyMethod() {
    return MethodSpec.methodBuilder("newReferenceKey")
        .addJavadoc("Returns a key suitable for inserting into the cache. If the cache holds"
            + " keys strongly then\nthe key is returned. If the cache holds keys weakly "
            + "then a {@link $T}\nholding the key argument is returned.\n", referenceKeyType)
        .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
        .addParameter(kTypeVar, "key")
        .addParameter(kRefQueueType, "referenceQueue")
        .addStatement("return $L", "key")
        .returns(Object.class)
        .build();
  }

  private void addNewFactoryMethods() {
    nodeFactory.addMethod(MethodSpec.methodBuilder("newFactory")
        .addJavadoc("Returns a factory optimized for the specified features.\n")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(BUILDER_PARAM)
        .addParameter(boolean.class, "isAsync")
        .addCode(NodeSelectorCode.get())
        .returns(NODE_FACTORY)
        .build());
    nodeFactory.addMethod(MethodSpec.methodBuilder("weakValues")
        .addJavadoc("Returns whether this factory supports weak values.\n")
        .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
        .addStatement("return false")
        .returns(boolean.class)
        .build());
    nodeFactory.addMethod(MethodSpec.methodBuilder("softValues")
        .addJavadoc("Returns whether this factory supports soft values.\n")
        .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
        .addStatement("return false")
        .returns(boolean.class)
        .build());
  }

  private void generatedNodes() {
    NavigableMap<String, Set<Feature>> classNameToFeatures = getClassNameToFeatures();
    classNameToFeatures.forEach((className, features) -> {
      String higherKey = classNameToFeatures.higherKey(className);
      boolean isLeaf = (higherKey == null) || !higherKey.startsWith(className);
      TypeSpec nodeSpec = makeNodeSpec(className, isLeaf, features);
      nodeTypes.add(nodeSpec);
    });
  }

  private NavigableMap<String, Set<Feature>> getClassNameToFeatures() {
    NavigableMap<String, Set<Feature>> classNameToFeatures = new TreeMap<>();

    for (List<Object> combination : combinations()) {
      Set<Feature> features = getFeatures(combination);
      String className = Feature.makeClassName(features);
      classNameToFeatures.put(encode(className), ImmutableSet.copyOf(features));
    }
    return classNameToFeatures;
  }

  private Set<Feature> getFeatures(List<Object> combination) {
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
    return features;
  }

  private TypeSpec makeNodeSpec(String className, boolean isFinal, Set<Feature> features) {
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
          encode(Feature.makeClassName(parentFeatures))), kTypeVar, vTypeVar);
    }

    NodeContext context = new NodeContext(superClass, className,
        isFinal, parentFeatures, generateFeatures);
    for (NodeRule rule : rules) {
      rule.accept(context);
    }
    return context.nodeSubtype.build();
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

  /** Returns an encoded form of the class name for compact use. */
  private static String encode(String className) {
    return Feature.makeEnumName(className)
        .replaceFirst("STRONG_KEYS", "P") // puissant
        .replaceFirst("WEAK_KEYS", "F") // faible
        .replaceFirst("_STRONG_VALUES", "S")
        .replaceFirst("_WEAK_VALUES", "W")
        .replaceFirst("_SOFT_VALUES", "D") // doux
        .replaceFirst("_EXPIRE_ACCESS", "A")
        .replaceFirst("_EXPIRE_WRITE", "W")
        .replaceFirst("_REFRESH_WRITE", "R")
        .replaceFirst("_MAXIMUM", "M")
        .replaceFirst("_WEIGHT", "W")
        .replaceFirst("_SIZE", "S");
  }

  public static void main(String[] args) throws IOException {
    new NodeFactoryGenerator(Paths.get(args[0])).generate();
  }
}
