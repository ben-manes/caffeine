/*
 * Copyright 2026 Ben Manes. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.toImmutableEnumSet;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;

/**
 * Generates the specialized cache types for every supported configuration.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class FactoryGenerator {
  private final ImmutableList<ImmutableSet<Feature>> featureCombinations;
  private final ImmutableList<Feature> canonicalOrder;
  private final UnaryOperator<String> encode;
  private final SpecFactory specFactory;
  private final Path directory;

  FactoryGenerator(Path directory, ImmutableList<ImmutableSet<Optional<Feature>>> dimensions,
      UnaryOperator<String> encode, SpecFactory specFactory) {
    requireNonNull(dimensions);
    this.featureCombinations = Sets.cartesianProduct(dimensions).stream()
        .map(FactoryGenerator::toFeatures).collect(toImmutableList());
    this.specFactory = requireNonNull(specFactory);
    this.canonicalOrder = dimensions.stream()
        .flatMap(Collection::stream)
        .flatMap(Optional::stream)
        .collect(toImmutableList());
    this.directory = requireNonNull(directory);
    this.encode = requireNonNull(encode);
  }

  void generate() throws IOException {
    var types = generateTypes();
    writeJavaFile(types);
    reformat();
  }

  /** Generates a specialization per configuration, ordered so that a parent precedes its child. */
  private ImmutableList<TypeSpec> generateTypes() {
    var classNameToFeatures = classNameToFeatures();
    var types = ImmutableList.<TypeSpec>builderWithExpectedSize(classNameToFeatures.size());
    classNameToFeatures.forEach((className, features) -> {
      var parentFeatures = parentFeatures(features);
      var parentClassName = classNameOf(parentFeatures);
      var higherKey = classNameToFeatures.higherKey(className);
      boolean isLeaf = (higherKey == null) || !higherKey.startsWith(className);
      checkInheritance(className, parentClassName);
      types.add(specFactory.create(className, parentClassName,
          isLeaf, parentFeatures, generateFeatures(features)));
    });
    return types.build();
  }

  /** Maps each encoded class name to its features, sorted so a parent sorts before its child. */
  private ImmutableSortedMap<String, ImmutableSet<Feature>> classNameToFeatures() {
    var classNameToFeatures = ImmutableSortedMap.<String, ImmutableSet<Feature>>naturalOrder();
    for (var features : featureCombinations) {
      classNameToFeatures.put(classNameOf(features), features);
    }
    return classNameToFeatures.buildOrThrow();
  }

  /** Returns the encoded class name, naming the features in canonical (not set iteration) order. */
  private String classNameOf(ImmutableSet<Feature> features) {
    return encode.apply(Feature.makeClassName(canonical(features)));
  }

  /** Orders the present features by the matrix dimension order, regardless of the set's own order. */
  private ImmutableList<Feature> canonical(ImmutableSet<Feature> features) {
    return canonicalOrder.stream().filter(features::contains).collect(toImmutableList());
  }

  /** Returns the inherited features owned by the parent specialization. */
  private ImmutableSet<Feature> parentFeatures(ImmutableSet<Feature> features) {
    var ordered = canonical(features);
    return (ordered.size() == 2)
        ? ImmutableSet.of()
        : Sets.immutableEnumSet(ordered.subList(0, ordered.size() - 1));
  }

  /** Returns the features that this specialization adds on top of its parent. */
  private ImmutableSet<Feature> generateFeatures(ImmutableSet<Feature> features) {
    var ordered = canonical(features);
    return (ordered.size() == 2)
        ? Sets.immutableEnumSet(ordered)
        : Sets.immutableEnumSet(ordered.subList(ordered.size() - 1, ordered.size()));
  }

  /** Asserts the prefix-coding invariant the inheritance and leaf detection rely on. */
  private static void checkInheritance(String className, String parentClassName) {
    checkState(parentClassName.isEmpty()
            || (className.startsWith(parentClassName) && !className.equals(parentClassName)),
        "Encoded parent '%s' must be a proper prefix of child '%s'", parentClassName, className);
  }

  private void writeJavaFile(ImmutableList<TypeSpec> types) throws IOException {
    var header = Resources.toString(Resources.getResource("license.txt"), UTF_8).trim();
    var timeZone = ZoneId.of("America/Los_Angeles");
    for (TypeSpec typeSpec : types) {
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
    if (Runtime.version().pre().isPresent()) {
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

  /** The choices for a required dimension: exactly one of the alternatives. */
  static ImmutableSet<Optional<Feature>> oneOf(Feature... features) {
    return Arrays.stream(features).map(Optional::of).collect(toImmutableSet());
  }

  /** The choices for an optional dimension: absent, or exactly one of the alternatives. */
  static ImmutableSet<Optional<Feature>> optional(Feature feature, Feature... features) {
    return Stream.concat(Stream.of(Optional.<Feature>empty(), Optional.of(feature)),
        Arrays.stream(features).map(Optional::of)).collect(toImmutableSet());
  }

  /** Flattens the present features of a configuration; the order is imposed later by canonical(). */
  private static ImmutableSet<Feature> toFeatures(Collection<Optional<Feature>> combination) {
    return combination.stream().flatMap(Optional::stream).collect(toImmutableEnumSet());
  }

  @FunctionalInterface
  interface SpecFactory {
    /** Returns the specialized type for a single configuration in the matrix. */
    TypeSpec create(String className, String parentClassName, boolean isFinal,
        ImmutableSet<Feature> parentFeatures, ImmutableSet<Feature> generateFeatures);
  }
}
