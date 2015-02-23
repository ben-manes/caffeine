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
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.Specifications.Strength;
import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

/**
 * Generates a factory that creates the cache optimized for the user specified configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheFactoryGenerator {
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
    addFactoryMethods();
    generateLocalCaches();

    writeJavaFile();
  }

  private void addFactoryMethods() {
    factory.addMethod(newBoundedLocalCache());
    factory.addMethod(MethodSpec.methodBuilder("create")
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(BUILDER_PARAM)
        .addParameter(CACHE_LOADER_PARAM)
        .returns(BOUNDED_LOCAL_CACHE)
        .addAnnotation(Nonnull.class)
        .addModifiers(Modifier.ABSTRACT)
        .addJavadoc("Returns a cache optimized for this configuration.\n")
        .build());
  }

  private MethodSpec newBoundedLocalCache() {
    return MethodSpec.methodBuilder("newBoundedLocalCache")
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .returns(BOUNDED_LOCAL_CACHE)
        .addModifiers(Modifier.STATIC)
        .addCode(CacheSelectorCode.get())
        .addParameter(BUILDER_PARAM)
        .addParameter(CACHE_LOADER_PARAM)
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
    Set<Strength> keyStrengths = ImmutableSet.of(Strength.STRONG, Strength.WEAK);
    Set<Strength> valueStrengths = ImmutableSet.of(Strength.STRONG, Strength.WEAK, Strength.SOFT);
    Set<Boolean> cacheLoaders = ImmutableSet.of(true, false);

    @SuppressWarnings("unchecked")
    Set<List<Object>> combinations = Sets.cartesianProduct(keyStrengths, valueStrengths,
        cacheLoaders);
    return combinations;
  }

  private void generateLocalCaches() {
    for (List<Object> combination : combinations()) {
      addLocalCacheSpec(
          (Strength) combination.get(0),
          (Strength) combination.get(1),
          (Boolean) combination.get(2));
    }
  }

  private void addLocalCacheSpec(Strength keyStrength, Strength valueStrength,
      boolean cacheLoader) {
    String enumName = makeEnumName(keyStrength, valueStrength, cacheLoader);
    String className = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, enumName);
    if (!seen.add(className)) {
      // skip duplicates
      return;
    }
    factory.addEnumConstant(enumName,TypeSpec.anonymousClassBuilder("")
        .addMethod(MethodSpec.methodBuilder("create")
            .addTypeVariable(kTypeVar).addTypeVariable(vTypeVar)
            .addAnnotation(Override.class)
            .returns(BOUNDED_LOCAL_CACHE)
            .addParameter(BUILDER_PARAM)
            .addParameter(CACHE_LOADER_PARAM)
            .addStatement("return new $N<>(builder, cacheLoader)", className)
            .build())
        .build());

    LocalCacheGenerator generator = new LocalCacheGenerator(className, keyStrength, valueStrength,
        cacheLoader);
    factory.addType(generator.generate());
  }

  private String makeEnumName(Strength keyStrength, Strength valueStrength, boolean cacheLoader) {
    StringBuilder name = new StringBuilder(keyStrength + "_KEYS");
    if (valueStrength == Strength.STRONG) {
      name.append("_STRONG_VALUES");
    } else {
      name.append("_INFIRM_VALUES");
    }
    if (cacheLoader) {
      name.append("_LOADING");
    }
    return name.toString();
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
