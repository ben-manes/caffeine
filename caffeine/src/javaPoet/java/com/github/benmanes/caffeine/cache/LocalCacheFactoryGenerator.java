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
import static com.github.benmanes.caffeine.cache.Specifications.BUILDER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.CACHE_LOADER_PARAM;
import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
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
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.lang.model.element.Modifier;

import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
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
  enum Feature {
    STRONG_KEYS(0), WEAK_KEYS(0),
    STRONG_VALUES(1), INFIRM_VALUES(1),
    LOADING(2),
    LISTENING(3),
    EXECUTOR(4),
    STATS(5),
    MAXIMUM_SIZE(6),
    MAXIMUM_WEIGHT(7),
    EXPIRE_ACCESS(8),
    EXPIRE_WRITE(9),
    REFRESH_WRITE(10);

    static Feature[] featureByIndex;

    int index;
    Feature(int index) {
      this.index = index;
    }

    static Feature forIndex(int index) {
      Preconditions.checkArgument(index > 1);
      if (featureByIndex == null) {
        featureByIndex = new Feature[Feature.values().length];
        for (Feature feature : values()) {
          featureByIndex[feature.index] = feature;
        }
      }
      return featureByIndex[index];
    }
  }

  final Path directory;
  final Set<String> seen;
  TypeSpec.Builder factory;

  public LocalCacheFactoryGenerator(Path directory) {
    this.directory = requireNonNull(directory);
    this.seen = new HashSet<>();
  }

  void generate() throws IOException {
    factory = TypeSpec.enumBuilder("LocalCacheFactory");
    addClassJavaDoc();
    generateLocalCaches();

    addFactoryMethods();
    writeJavaFile();
  }

  private void addFactoryMethods() {
    factory.addMethod(newBoundedLocalCache());
    factory.addMethod(MethodSpec.methodBuilder("create")
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(BUILDER_PARAM)
        .addParameter(CACHE_LOADER_PARAM)
        .addParameter(boolean.class, "async")
        .returns(BOUNDED_LOCAL_CACHE)
        .addModifiers(Modifier.ABSTRACT)
        .addJavadoc("Returns a cache optimized for this configuration.\n")
        .build());
  }

  private MethodSpec newBoundedLocalCache() {
    OptionalInt bufferSize = seen.stream().mapToInt(s -> s.length()).max();
    Preconditions.checkState(bufferSize.isPresent(), "Must generate all cache types first");
    return MethodSpec.methodBuilder("newBoundedLocalCache")
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .returns(BOUNDED_LOCAL_CACHE)
        .addModifiers(Modifier.STATIC)
        .addCode(CacheSelectorCode.get(bufferSize.getAsInt()))
        .addParameter(BUILDER_PARAM)
        .addParameter(CACHE_LOADER_PARAM)
        .addParameter(boolean.class, "async")
        .addJavadoc("Returns a cache optimized for this configuration.\n")
        .build();
  }

  private void writeJavaFile() throws IOException {
    JavaFile.builder(getClass().getPackage().getName(), factory.build())
        .addFileComment("Copyright $L Ben Manes. All Rights Reserved.", Year.now())
        .indent("  ")
        .build()
        .writeTo(directory);
  }

  private Set<List<Object>> combinations() {
    Set<Boolean> keyStrengths = ImmutableSet.of(true, false);
    Set<Boolean> valueStrengths = ImmutableSet.of(true, false);
    Set<Boolean> cacheLoaders = ImmutableSet.of(true, false);
    Set<Boolean> removalListeners = ImmutableSet.of(true, false);
    Set<Boolean> executors = ImmutableSet.of(true, false);
    Set<Boolean> stats = ImmutableSet.of(true, false);
    Set<Boolean> maximumSize = ImmutableSet.of(true, false);
    Set<Boolean> weighted = ImmutableSet.of(true, false);
    Set<Boolean> expireAfterAccess = ImmutableSet.of(true, false);
    Set<Boolean> expireAfterWrite = ImmutableSet.of(true, false);
    Set<Boolean> refreshAfterWrite = ImmutableSet.of(true, false);

    @SuppressWarnings("unchecked")
    Set<List<Object>> combinations = Sets.cartesianProduct(keyStrengths, valueStrengths,
        cacheLoaders, removalListeners, executors, stats, maximumSize, weighted, expireAfterAccess,
        expireAfterWrite, refreshAfterWrite);
    return combinations;
  }

  private void generateLocalCaches() {
    for (List<Object> combination : combinations()) {
      Set<Feature> features = new LinkedHashSet<>();

      features.add(((Boolean) combination.get(0)) ? Feature.STRONG_KEYS : Feature.WEAK_KEYS);
      features.add(((Boolean) combination.get(1)) ? Feature.STRONG_VALUES : Feature.INFIRM_VALUES);
      for (int i = 2; i < combination.size(); i++) {
        if ((Boolean) combination.get(i)) {
          features.add(Feature.forIndex(i));
        }
      }
      if (features.contains(Feature.MAXIMUM_WEIGHT)) {
        features.remove(Feature.MAXIMUM_SIZE);
      }

      addLocalCacheSpec(features);
    }
  }

  private void addLocalCacheSpec(Set<Feature> features) {
    String enumName = makeEnumName(features);
    if (!seen.add(enumName)) {
      return;
    }
    String className = makeClassName(features);
    factory.addEnumConstant(enumName, TypeSpec.anonymousClassBuilder("")
        .addMethod(MethodSpec.methodBuilder("create")
            .addTypeVariable(kTypeVar).addTypeVariable(vTypeVar)
            .returns(BOUNDED_LOCAL_CACHE)
            .addParameter(BUILDER_PARAM)
            .addParameter(CACHE_LOADER_PARAM)
            .addParameter(boolean.class, "async")
            .addStatement("return new $N<>(builder, cacheLoader, async)", className)
            .build())
        .build());

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
      superClass = ParameterizedTypeName.get(ClassName.get(PACKAGE_NAME,
          makeClassName(parentFeatures)), kTypeVar, vTypeVar);
    }
    LocalCacheGenerator generator = new LocalCacheGenerator(
        superClass, className, parentFeatures, generateFeatures);
    factory.addType(generator.generate());
  }

  private String makeEnumName(Iterable<Feature> features) {
    return StreamSupport.stream(features.spliterator(), false)
        .map(feature -> feature.name())
        .collect(Collectors.joining("_"));
  }

  private String makeClassName(Iterable<Feature> features) {
    String enumName = makeEnumName(features);
    return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, enumName);
  }

  private void addClassJavaDoc() {
    factory.addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for caches optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n");
  }

  public static void main(String[] args) throws IOException {
    new LocalCacheFactoryGenerator(Paths.get(args[0])).generate();
  }
}
