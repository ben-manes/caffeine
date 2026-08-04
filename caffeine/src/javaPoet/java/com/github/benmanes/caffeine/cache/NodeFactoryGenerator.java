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
import static com.github.benmanes.caffeine.cache.Specifications.PACKAGE_NAME;
import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.vTypeVar;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

/**
 * Generates the cache entry's specialized type. These entries are optimized for the configuration
 * to minimize memory use.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeFactoryGenerator {

  private static final ImmutableMap<Feature, String> ENCODING_MAP = ImmutableMap.of(
      Feature.STRONG_KEYS, /* puissant */ "P",
      Feature.WEAK_KEYS, /* faible */ "F",
      Feature.STRONG_VALUES, "S",
      Feature.WEAK_VALUES, "W",
      Feature.SOFT_VALUES, /* doux */ "D",
      Feature.EXPIRE_ACCESS, "A",
      Feature.EXPIRE_WRITE, "W",
      Feature.REFRESH_WRITE, "R",
      Feature.MAXIMUM_SIZE, "MS",
      Feature.MAXIMUM_WEIGHT, "MW");

  private final ImmutableList<Rule<NodeContext>> rules;
  private final ImmutableList<ImmutableSet<Optional<Feature>>> dimensions;

  NodeFactoryGenerator() {
    // The matrix dimensions in canonical naming order for matching to class names
    dimensions = ImmutableList.of(oneOf(Feature.STRONG_KEYS, Feature.WEAK_KEYS),
        oneOf(Feature.STRONG_VALUES, Feature.WEAK_VALUES, Feature.SOFT_VALUES),
        optional(Feature.EXPIRE_ACCESS), optional(Feature.EXPIRE_WRITE),
        optional(Feature.REFRESH_WRITE), optional(Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT));
    rules = ImmutableList.of(new AddSubtype(), new AddConstructors(), new AddKey(), new AddValue(),
        new AddMaximum(), new AddExpiration(), new AddDeques(), new AddFactoryMethods(),
        new AddHealth(), new Finalize());
  }

  private void generate(Path directory) throws IOException {
    var generator = new FactoryGenerator(directory, dimensions, ENCODING_MAP, this::makeNodeSpec);
    generator.generate();
  }

  private TypeSpec makeNodeSpec(String className, String parentClassName, boolean isFinal,
      ImmutableSet<Feature> parentFeatures, ImmutableSet<Feature> generateFeatures) {
    TypeName superClass = parentFeatures.isEmpty()
        ? ClassName.OBJECT
        : ParameterizedTypeName.get(
            ClassName.get(PACKAGE_NAME, parentClassName), kTypeVar, vTypeVar);
    var context = new NodeContext(superClass, className,
        isFinal, parentFeatures, generateFeatures);
    for (var rule : rules) {
      rule.run(context);
    }
    return context.build();
  }

  public static void main(String[] args) throws IOException {
    new NodeFactoryGenerator().generate(Path.of(args[0]));
  }
}
