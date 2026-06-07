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

import static com.github.benmanes.caffeine.cache.FactoryGenerator.oneOf;
import static com.github.benmanes.caffeine.cache.FactoryGenerator.optional;
import static com.github.benmanes.caffeine.cache.Specifications.BOUNDED_LOCAL_CACHE;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.commons.text.TextStringBuilder;

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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

/**
 * Generates a factory that creates the cache optimized for the user-specified configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class LocalCacheFactoryGenerator {
  private final ImmutableList<Rule<LocalCacheContext>> rules;
  private final ImmutableList<ImmutableSet<Optional<Feature>>> dimensions;

  LocalCacheFactoryGenerator() {
    // The matrix dimensions in canonical naming order for matching to class names
    dimensions = ImmutableList.of(oneOf(Feature.STRONG_KEYS, Feature.WEAK_KEYS),
        oneOf(Feature.STRONG_VALUES, Feature.INFIRM_VALUES), optional(Feature.LISTENING),
        optional(Feature.STATS), optional(Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT),
        optional(Feature.EXPIRE_ACCESS), optional(Feature.EXPIRE_WRITE),
        optional(Feature.REFRESH_WRITE));
    rules = ImmutableList.of(new AddSubtype(), new AddConstructor(), new AddKeyValueStrength(),
        new AddRemovalListener(), new AddStats(), new AddExpirationTicker(), new AddMaximum(),
        new AddFastPath(), new AddDeques(), new AddExpireAfterAccess(), new AddExpireAfterWrite(),
        new AddRefreshAfterWrite(), new AddPacer(), new Finalize());
  }

  private void generate(Path directory) throws IOException {
    var generator = new FactoryGenerator(directory, dimensions,
        LocalCacheFactoryGenerator::encode, this::makeLocalCacheSpec);
    generator.generate();
  }

  private TypeSpec makeLocalCacheSpec(String className, String parentClassName, boolean isFinal,
      ImmutableSet<Feature> parentFeatures, ImmutableSet<Feature> generateFeatures) {
    TypeName superClass = parentFeatures.isEmpty()
        ? BOUNDED_LOCAL_CACHE
        : ParameterizedTypeName.get(ClassName.bestGuess(parentClassName), kTypeVar, vTypeVar);
    var context = new LocalCacheContext(superClass,
        className, isFinal, parentFeatures, generateFeatures);
    for (var rule : rules) {
      rule.run(context);
    }
    return context.build();
  }

  /** Returns an encoded form of the class name for compact use. */
  private static String encode(String className) {
    return new TextStringBuilder(Feature.makeEnumName(className))
        .replaceFirst("STRONG_KEYS", "S")
        .replaceFirst("WEAK_KEYS", "W")
        .replaceFirst("STRONG_VALUES", "S")
        .replaceFirst("INFIRM_VALUES", "I")
        .replaceFirst("LISTENING", "L")
        .replaceFirst("STATS", "S")
        .replaceFirst("MAXIMUM", "M")
        .replaceFirst("WEIGHT", "W")
        .replaceFirst("SIZE", "S")
        .replaceFirst("EXPIRE_ACCESS", "A")
        .replaceFirst("EXPIRE_WRITE", "W")
        .replaceFirst("REFRESH_WRITE", "R")
        .deleteAll("_")
        .toString();
  }

  public static void main(String[] args) throws IOException {
    new LocalCacheFactoryGenerator().generate(Path.of(args[0]));
  }
}
