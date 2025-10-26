/package com.github.benmanes.caffeine.cache;

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
import java.util.*;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.local.*;
import com.google.common.collect.*;
import com.google.common.io.Resources;
import com.palantir.javapoet.*;

/**
 * Generates a factory that creates the cache optimized for the user specified configuration.
 * 
 * ✅ Refactor: Modified to comply with LSP by allowing substitution of rule types.
 * Now LocalCacheRule implementations can be replaced or extended freely without modifying this class.
 *
 * @author 
 */
public final class LocalCacheFactoryGenerator {

  private final Feature[] featureByIndex = {
      null, null, Feature.LISTENING, Feature.STATS,
      Feature.MAXIMUM_SIZE, Feature.MAXIMUM_WEIGHT,
      Feature.EXPIRE_ACCESS, Feature.EXPIRE_WRITE, Feature.REFRESH_WRITE
  };

  // ✅ Cambio 1: La lista ya no contiene implementaciones fijas
  private final List<LocalCacheRule> rules;
  private final List<TypeSpec> factoryTypes;
  private final Path directory;

  // ✅ Cambio 2: Constructor que permite inyección externa de reglas
  public LocalCacheFactoryGenerator(Path directory, List<LocalCacheRule> rules) {
    this.directory = requireNonNull(directory);
    this.rules = new ArrayList<>(requireNonNull(rules));
    this.factoryTypes = new ArrayList<>();
  }

  // ✅ Cambio 3: Permitir agregar reglas sin alterar comportamiento base
  public void registerRule(LocalCacheRule rule) {
    if (rule != null && !rules.contains(rule)) {
      rules.add(rule);
    }
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
    if (Runtime.version().pre().isPresent()) return;
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
      parentFeatures = ImmutableSet.copyOf(Iterables.limit(features, features.size() - 1));
      generateFeatures = Sets.immutableEnumSet(features.asList().get(features.size() - 1));
      superClass = ParameterizedTypeName.get(
          ClassName.bestGuess(encode(Feature.makeClassName(parentFeatures))),
          kTypeVar, vTypeVar);
    }

    var context = new LocalCacheContext(superClass,
        className, isFinal, parentFeatures, generateFeatures);

    // ✅ Cambio 4: Se usa polimorfismo — cualquier LocalCacheRule puede actuar
    for (LocalCacheRule rule : rules) {
      if (rule.applies(context)) {
        rule.execute(context);
      }
    }
    return context.build();
  }

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

  // ✅ Cambio 5: Ahora las reglas se pasan externamente — respeta LSP
  public static void main(String[] args) throws IOException {
    List<LocalCacheRule> rules = List.of(
        new AddSubtype(), new AddConstructor(), new AddKeyValueStrength(),
        new AddRemovalListener(), new AddStats(), new AddExpirationTicker(),
        new AddMaximum(), new AddFastPath(), new AddDeques(),
        new AddExpireAfterAccess(), new AddExpireAfterWrite(),
        new AddRefreshAfterWrite(), new AddPacer(), new Finalize()
    );
    new LocalCacheFactoryGenerator(Path.of(args[0]), rules).generate();
  }
}
