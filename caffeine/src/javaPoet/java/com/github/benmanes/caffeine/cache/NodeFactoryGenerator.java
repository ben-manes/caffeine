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
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.spi.ToolProvider;
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
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

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
public final class NodeFactoryGenerator {
  private final List<NodeRule> rules = List.of(new AddSubtype(), new AddConstructors(),
      new AddKey(), new AddValue(), new AddMaximum(), new AddExpiration(), new AddDeques(),
      new AddFactoryMethods(),  new AddHealth(), new Finalize());
  private final Feature[] featureByIndex = { null, null, Feature.EXPIRE_ACCESS,
      Feature.EXPIRE_WRITE, Feature.REFRESH_WRITE, Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT };
  private final List<TypeSpec> nodeTypes;
  private final Path directory;

  private NodeFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
    this.nodeTypes = new ArrayList<>();
  }

  private void generate() throws IOException {
    generatedNodes();
    writeJavaFile();
    reformat();
  }

  private void writeJavaFile() throws IOException {
    String header = Resources.toString(Resources.getResource("license.txt"), UTF_8).trim();
    var timeZone = ZoneId.of("America/Los_Angeles");
    for (TypeSpec node : nodeTypes) {
      JavaFile.builder(getClass().getPackage().getName(), node)
          .addFileComment(header, Year.now(timeZone))
          .skipJavaLangImports(true)
          .indent("  ")
          .build()
          .writeTo(directory);
    }
  }

  @SuppressWarnings("SystemOut")
  private void reformat() throws IOException {
    if (Boolean.parseBoolean(System.getenv("JDK_EA"))) {
      return; // may be incompatible for EA builds
    }
    try (Stream<Path> stream = Files.walk(directory)) {
      ImmutableList<String> files = stream
          .map(Path::toString)
          .filter(path -> path.endsWith(".java"))
          .collect(toImmutableList());
      ToolProvider.findFirst("google-java-format").ifPresent(formatter -> {
        int result = formatter.run(System.out, System.err,
            Stream.concat(Stream.of("-i"), files.stream()).toArray(String[]::new));
        checkState(result == 0, "Java formatting failed with %s exit code", result);
      });
    }
  }

  private void generatedNodes() {
    NavigableMap<String, ImmutableSet<Feature>> classNameToFeatures = getClassNameToFeatures();
    classNameToFeatures.forEach((className, features) -> {
      String higherKey = classNameToFeatures.higherKey(className);
      boolean isLeaf = (higherKey == null) || !higherKey.startsWith(className);
      TypeSpec nodeSpec = makeNodeSpec(className, isLeaf, features);
      nodeTypes.add(nodeSpec);
    });
  }

  private NavigableMap<String, ImmutableSet<Feature>> getClassNameToFeatures() {
    var classNameToFeatures = new TreeMap<String, ImmutableSet<Feature>>();
    for (List<Object> combination : combinations()) {
      var features = getFeatures(combination);
      var className = Feature.makeClassName(features);
      classNameToFeatures.put(encode(className), features);
    }
    return classNameToFeatures;
  }

  @SuppressWarnings("SetsImmutableEnumSetIterable")
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
    // In featureByIndex order for class naming
    return ImmutableSet.copyOf(features);
  }

  @SuppressWarnings("SetsImmutableEnumSetIterable")
  private TypeSpec makeNodeSpec(String className, boolean isFinal, ImmutableSet<Feature> features) {
    TypeName superClass;
    ImmutableSet<Feature> parentFeatures;
    ImmutableSet<Feature> generateFeatures;
    if (features.size() == 2) {
      parentFeatures = ImmutableSet.of();
      generateFeatures = features;
      superClass = ClassName.OBJECT;
    } else {
      // Requires that parentFeatures is in featureByIndex order for super class naming
      parentFeatures = ImmutableSet.copyOf(Iterables.limit(features, features.size() - 1));
      generateFeatures = Sets.immutableEnumSet(features.asList().get(features.size() - 1));
      superClass = ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME,
          encode(Feature.makeClassName(parentFeatures))), kTypeVar, vTypeVar);
    }

    var context = new NodeContext(superClass, className, isFinal, parentFeatures, generateFeatures);
    for (NodeRule rule : rules) {
      if (rule.applies(context)) {
        rule.execute(context);
      }
    }
    return context.build();
  }

  private static Set<List<Object>> combinations() {
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

  public static void main(String[] args) throws IOException {
    new NodeFactoryGenerator(Path.of(args[0])).generate();
  }
}
