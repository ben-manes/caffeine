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

import static com.github.benmanes.caffeine.cache.Specifications.BOUNDED_LOCAL_CACHE;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.local.AddConstructor;
import com.github.benmanes.caffeine.cache.local.AddDeques;
import com.github.benmanes.caffeine.cache.local.AddExpirationTicker;
import com.github.benmanes.caffeine.cache.local.AddExpireAfterAccess;
import com.github.benmanes.caffeine.cache.local.AddExpireAfterWrite;
import com.github.benmanes.caffeine.cache.local.AddFastPath;
import com.github.benmanes.caffeine.cache.local.AddKeyValueStrength;
import com.github.benmanes.caffeine.cache.local.AddMaximum;
import com.github.benmanes.caffeine.cache.local.AddPacer;
import com.github.benmanes.caffeine.cache.local.AddRefreshAfterWrite;
import com.github.benmanes.caffeine.cache.local.AddRemovalListener;
import com.github.benmanes.caffeine.cache.local.AddStats;
import com.github.benmanes.caffeine.cache.local.AddSubtype;
import com.github.benmanes.caffeine.cache.local.Finalize;
import com.github.benmanes.caffeine.cache.local.LocalCacheContext;
import com.github.benmanes.caffeine.cache.local.LocalCacheRule;
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
 * Generates a factory that creates the cache optimized for the user specified configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheFactoryGenerator {
  private final Feature[] featureByIndex = { null, null, Feature.LISTENING, Feature.STATS,
      Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT, Feature.EXPIRE_ACCESS,
      Feature.EXPIRE_WRITE, Feature.REFRESH_WRITE};
  private final List<LocalCacheRule> rules = List.of(new AddSubtype(), new AddConstructor(),
      new AddKeyValueStrength(), new AddRemovalListener(), new AddStats(),
      new AddExpirationTicker(), new AddMaximum(), new AddFastPath(), new AddDeques(),
      new AddExpireAfterAccess(), new AddExpireAfterWrite(), new AddRefreshAfterWrite(),
      new AddPacer(), new Finalize());
  private final List<TypeSpec> factoryTypes;
  private final Path directory;

  private LocalCacheFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
    this.factoryTypes = new ArrayList<>();
  }

  private void generate() throws IOException {
    generateLocalCaches();
    writeJavaFile();
    reformat();
  }

  private void writeJavaFile() throws IOException {
    var header = Resources.toString(Resources.getResource("license.txt"), UTF_8).trim();
    var timeZone = ZoneId.of("America/Los_Angeles");
    for (TypeSpec typeSpec : factoryTypes) {
      JavaFile.builder(getClass().getPackageName(), typeSpec)
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

  private void generateLocalCaches() {
    NavigableMap<String, ImmutableSet<Feature>> classNameToFeatures = getClassNameToFeatures();
    classNameToFeatures.forEach((className, features) -> {
      String higherKey = classNameToFeatures.higherKey(className);
      boolean isLeaf = (higherKey == null) || !higherKey.startsWith(className);
      TypeSpec cacheSpec = makeLocalCacheSpec(className, isLeaf, features);
      factoryTypes.add(cacheSpec);
    });
  }

  private NavigableMap<String, ImmutableSet<Feature>> getClassNameToFeatures() {
    var classNameToFeatures = new TreeMap<String, ImmutableSet<Feature>>();
    for (List<Object> combination : combinations()) {
      ImmutableSet<Feature> features = getFeatures(combination);
      String className = encode(Feature.makeClassName(features));
      classNameToFeatures.put(className, features);
    }
    return classNameToFeatures;
  }

  @SuppressWarnings("SetsImmutableEnumSetIterable")
  private ImmutableSet<Feature> getFeatures(List<Object> combination) {
    var features = new LinkedHashSet<Feature>();
    features.add(((Boolean) combination.get(0)) ? Feature.STRONG_KEYS : Feature.WEAK_KEYS);
    features.add(((Boolean) combination.get(1)) ? Feature.STRONG_VALUES : Feature.INFIRM_VALUES);
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

  private Set<List<Object>> combinations() {
    List<Set<Boolean>> sets = Collections.nCopies(featureByIndex.length, Set.of(true, false));
    return Sets.cartesianProduct(sets);
  }

  @SuppressWarnings("SetsImmutableEnumSetIterable")
  private TypeSpec makeLocalCacheSpec(String className,
      boolean isFinal, ImmutableSet<Feature> features) {
    TypeName superClass;
    ImmutableSet<Feature> parentFeatures;
    ImmutableSet<Feature> generateFeatures;
    if (features.size() == 2) {
      parentFeatures = ImmutableSet.of();
      generateFeatures = features;
      superClass = BOUNDED_LOCAL_CACHE;
    } else {
      // Requires that parentFeatures is in featureByIndex order for super class naming
      parentFeatures = ImmutableSet.copyOf(Iterables.limit(features, features.size() - 1));
      generateFeatures = Sets.immutableEnumSet(features.asList().get(features.size() - 1));
      superClass = ParameterizedTypeName.get(ClassName.bestGuess(
          encode(Feature.makeClassName(parentFeatures))), kTypeVar, vTypeVar);
    }

    var context = new LocalCacheContext(superClass,
        className, isFinal, parentFeatures, generateFeatures);
    for (LocalCacheRule rule : rules) {
      if (rule.applies(context)) {
        rule.execute(context);
      }
    }
    return context.build();
  }

  /** Returns an encoded form of the class name for compact use. */
  private static String encode(String className) {
    return Feature.makeEnumName(className)
        .replaceFirst("STRONG_KEYS", "S")
        .replaceFirst("WEAK_KEYS", "W")
        .replaceFirst("_STRONG_VALUES", "S")
        .replaceFirst("_INFIRM_VALUES", "I")
        .replaceFirst("_LISTENING", "L")
        .replaceFirst("_STATS", "S")
        .replaceFirst("_MAXIMUM", "M")
        .replaceFirst("_WEIGHT", "W")
        .replaceFirst("_SIZE", "S")
        .replaceFirst("_EXPIRE_ACCESS", "A")
        .replaceFirst("_EXPIRE_WRITE", "W")
        .replaceFirst("_REFRESH_WRITE", "R");
  }

  public static void main(String[] args) throws IOException {
    new LocalCacheFactoryGenerator(Path.of(args[0])).generate();
  }
}
