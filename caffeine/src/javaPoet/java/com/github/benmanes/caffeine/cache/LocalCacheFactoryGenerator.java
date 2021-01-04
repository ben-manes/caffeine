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
import static com.github.benmanes.caffeine.cache.Specifications.BUILDER;
import static com.github.benmanes.caffeine.cache.Specifications.BUILDER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.CACHE_LOADER;
import static com.github.benmanes.caffeine.cache.Specifications.CACHE_LOADER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;

import org.checkerframework.checker.nullness.qual.Nullable;

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
import com.github.benmanes.caffeine.cache.local.AddWriteBuffer;
import com.github.benmanes.caffeine.cache.local.Finalize;
import com.github.benmanes.caffeine.cache.local.LocalCacheContext;
import com.github.benmanes.caffeine.cache.local.LocalCacheRule;
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
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * Generates a factory that creates the cache optimized for the user specified configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheFactoryGenerator {
  static final FieldSpec LOOKUP = FieldSpec.builder(MethodHandles.Lookup.class, "LOOKUP")
      .initializer("$T.lookup()", MethodHandles.class)
      .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
      .build();
  static final FieldSpec FACTORY = FieldSpec.builder(MethodType.class, "FACTORY")
      .initializer("$T.methodType($T.class, $T.class, $T.class, $T.class)",
          MethodType.class, void.class, BUILDER, CACHE_LOADER.rawType, TypeName.BOOLEAN)
      .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
      .build();

  final Feature[] featureByIndex = { null, null, Feature.LISTENING, Feature.STATS,
      Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT, Feature.EXPIRE_ACCESS,
      Feature.EXPIRE_WRITE, Feature.REFRESH_WRITE};
  final List<LocalCacheRule> rules = ImmutableList.of(new AddSubtype(), new AddConstructor(),
      new AddKeyValueStrength(), new AddRemovalListener(), new AddStats(),
      new AddExpirationTicker(), new AddMaximum(), new AddFastPath(), new AddDeques(),
      new AddExpireAfterAccess(), new AddExpireAfterWrite(), new AddRefreshAfterWrite(),
      new AddWriteBuffer(), new AddPacer(), new Finalize());
  final ZoneId timeZone = ZoneId.of("America/Los_Angeles");
  final Path directory;

  TypeSpec.Builder factory;

  private final List<TypeSpec> factoryTypes;

  @SuppressWarnings("NullAway.Init")
  public LocalCacheFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
    this.factoryTypes = new ArrayList<>();
  }

  void generate() throws IOException {
    factory = TypeSpec.classBuilder("LocalCacheFactory")
        .addModifiers(Modifier.FINAL)
        .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
    addClassJavaDoc();
    addConstants();

    generateLocalCaches();
    addFactoryMethods();
    writeJavaFile();
    reformat();
  }

  private void addFactoryMethods() {
    factory.addMethod(MethodSpec.methodBuilder("newBoundedLocalCache")
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .returns(BOUNDED_LOCAL_CACHE)
        .addModifiers(Modifier.STATIC)
        .addCode(LocalCacheSelectorCode.get())
        .addParameter(BUILDER_PARAM)
        .addParameter(CACHE_LOADER_PARAM.toBuilder().addAnnotation(Nullable.class).build())
        .addParameter(boolean.class, "async")
        .addJavadoc("Returns a cache optimized for this configuration.\n")
        .build());
  }

  private void writeJavaFile() throws IOException {
    String header = Resources.toString(Resources.getResource("license.txt"), UTF_8).trim();
    JavaFile.builder(getClass().getPackage().getName(), factory.build())
        .addFileComment(header, Year.now(timeZone))
        .indent("  ")
        .build()
        .writeTo(directory);

    for (TypeSpec typeSpec : factoryTypes) {
      JavaFile.builder(getClass().getPackage().getName(), typeSpec)
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

  private void addConstants() {
    List<String> constants = ImmutableList.of("maximum", "windowMaximum", "mainProtectedMaximum",
        "weightedSize", "windowWeightedSize", "mainProtectedWeightedSize");
    for (String constant : constants) {
      String name = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, constant);
      factory.addField(FieldSpec.builder(String.class, name)
          .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
          .initializer("$S", constant)
          .build());
    }

    constants = ImmutableList.of("key", "value", "accessTime", "writeTime");
    for (String constant : constants) {
      String name = CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, constant);
      factory.addField(FieldSpec.builder(String.class, name)
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
              .initializer("$S", constant)
              .build());
    }

    factory.addField(FACTORY);
    factory.addField(LOOKUP);
  }

  private void generateLocalCaches() {
    NavigableMap<String, Set<Feature>> classNameToFeatures = getClassNameToFeatures();
    classNameToFeatures.forEach((className, features) -> {
      String higherKey = classNameToFeatures.higherKey(className);
      boolean isLeaf = (higherKey == null) || !higherKey.startsWith(className);
      TypeSpec cacheSpec = makeLocalCacheSpec(className, isLeaf, features);
      factoryTypes.add(cacheSpec);
    });
  }

  private NavigableMap<String, Set<Feature>> getClassNameToFeatures() {
    NavigableMap<String, Set<Feature>> classNameToFeatures = new TreeMap<>();
    for (List<Object> combination : combinations()) {
      Set<Feature> features = getFeatures(combination);
      String className = encode(Feature.makeClassName(features));
      classNameToFeatures.put(className, features);
    }
    return classNameToFeatures;
  }

  private Set<Feature> getFeatures(List<Object> combination) {
    Set<Feature> features = new LinkedHashSet<>();
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
    return features;
  }

  private Set<List<Object>> combinations() {
    Set<Boolean> options = ImmutableSet.of(true, false);
    List<Set<Boolean>> sets = Collections.nCopies(featureByIndex.length, options);
    return Sets.cartesianProduct(sets);
  }

  private TypeSpec makeLocalCacheSpec(String className, boolean isFinal, Set<Feature> features) {
    TypeName superClass;
    Set<Feature> parentFeatures;
    Set<Feature> generateFeatures;
    if (features.size() == 2) {
      parentFeatures = ImmutableSet.of();
      generateFeatures = features;
      superClass = BOUNDED_LOCAL_CACHE;
    } else {
      parentFeatures = ImmutableSet.copyOf(Iterables.limit(features, features.size() - 1));
      generateFeatures = ImmutableSet.of(Iterables.getLast(features));
      superClass = ParameterizedTypeName.get(ClassName.bestGuess(
          encode(Feature.makeClassName(parentFeatures))), kTypeVar, vTypeVar);
    }

    LocalCacheContext context = new LocalCacheContext(
        superClass, className, isFinal, parentFeatures, generateFeatures);
    for (LocalCacheRule rule : rules) {
      rule.accept(context);
    }
    return context.cache.build();
  }

  private void addClassJavaDoc() {
    factory.addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for caches optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n");
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
    new LocalCacheFactoryGenerator(Paths.get(args[0])).generate();
  }
}
