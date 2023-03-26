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

import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * Generates the cache entry's specialized type. These entries are optimized for the configuration
 * to minimize memory use. An entry may have any of the following properties:
 * <ul>
 *   <li>strong or weak key
 *   <li>strong, weak, or soft value
 *   <li>access timestamp
 *   <li>write timestamp
 *   <li>size queue type
 *   <li>list references
 *   <li>weight
 * </ul>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class NodeFactoryGenerator {
  private final List<NodeRule> rules = List.of(new AddSubtype(), new AddConstructors(),
      new AddKey(), new AddValue(), new AddMaximum(), new AddExpiration(), new AddDeques(),
      new AddFactoryMethods(),  new AddHealth(), new Finalize());
  private final Feature[] featureByIndex = { null, null, Feature.EXPIRE_ACCESS,
      Feature.EXPIRE_WRITE, Feature.REFRESH_WRITE, Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT };
  private final List<TypeSpec> nodeTypes;
  private final Path directory;

  @SuppressWarnings("NullAway.Init")
  private NodeFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
    this.nodeTypes = new ArrayList<>();
  }

  private void generate() throws FormatterException, IOException {
    generatedNodes();
    writeJavaFile();
    reformat();
  }

  private void writeJavaFile() throws IOException {
    String header = Resources.toString(Resources.getResource("license.txt"), UTF_8).trim();
    ZoneId timeZone = ZoneId.of("America/Los_Angeles");
    for (TypeSpec node : nodeTypes) {
      JavaFile.builder(getClass().getPackage().getName(), node)
          .addFileComment(header, Year.now(timeZone))
          .indent("  ")
          .build()
          .writeTo(directory);
    }
  }

  private void reformat() throws FormatterException, IOException {
    if (Boolean.parseBoolean(System.getenv("JDK_EA"))) {
      return; // may be incompatible for EA builds
    }
    try (Stream<Path> stream = Files.walk(directory)) {
      ImmutableList<Path> files = stream
          .filter(path -> path.toString().endsWith(".java"))
          .collect(toImmutableList());
      var formatter = new Formatter();
      for (Path file : files) {
        String source = Files.readString(file);
        String formatted = formatter.formatSourceAndFixImports(source);
        Files.writeString(file, formatted);
      }
    }
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
    var classNameToFeatures = new TreeMap<String, Set<Feature>>();
    for (List<Object> combination : combinations()) {
      var features = getFeatures(combination);
      var className = Feature.makeClassName(features);
      classNameToFeatures.put(encode(className), features);
    }
    return classNameToFeatures;
  }

  private ImmutableSet<Feature> getFeatures(List<Object> combination) {
    var features = new LinkedHashSet<Feature>();
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
    return ImmutableSet.copyOf(features);
  }

  @SuppressWarnings("NullAway")
  private TypeSpec makeNodeSpec(String className, boolean isFinal, Set<Feature> features) {
    TypeName superClass;
    Set<Feature> parentFeatures;
    Set<Feature> generateFeatures;
    if (features.size() == 2) {
      parentFeatures = Set.of();
      generateFeatures = features;
      superClass = TypeName.OBJECT;
    } else {
      parentFeatures = ImmutableSet.copyOf(Iterables.limit(features, features.size() - 1));
      generateFeatures = ImmutableSet.of(Iterables.getLast(features));
      superClass = ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME,
          encode(Feature.makeClassName(parentFeatures))), kTypeVar, vTypeVar);
    }

    var context = new NodeContext(superClass, className, isFinal, parentFeatures, generateFeatures);
    for (NodeRule rule : rules) {
      rule.accept(context);
    }
    return context.nodeSubtype.build();
  }

  private Set<List<Object>> combinations() {
    var keyStrengths = Set.of(Feature.STRONG_KEYS, Feature.WEAK_KEYS);
    var valueStrengths = Set.of(Feature.STRONG_VALUES, Feature.WEAK_VALUES, Feature.SOFT_VALUES);
    var expireAfterAccess = Set.of(false, true);
    var expireAfterWrite = Set.of(false, true);
    var refreshAfterWrite = Set.of(false, true);
    var maximumSize = Set.of(false, true);
    var weighed = Set.of(false, true);

    return Sets.cartesianProduct(keyStrengths, valueStrengths,
        expireAfterAccess, expireAfterWrite, refreshAfterWrite, maximumSize, weighed);
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

  public static void main(String[] args) throws FormatterException, IOException {
    new NodeFactoryGenerator(Paths.get(args[0])).generate();
  }
}
